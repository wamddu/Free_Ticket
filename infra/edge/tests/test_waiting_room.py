import base64
import hashlib
import hmac
import json
import os
import sys
import threading
import time

import boto3
import pytest
from moto import mock_aws

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'lambda'))

JWT_SECRET = 'a' * 64
QUEUE_SECRET = 'q' * 32
os.environ.update({'AWS_DEFAULT_REGION': 'ap-northeast-2', 'AWS_ACCESS_KEY_ID': 'test', 'AWS_SECRET_ACCESS_KEY': 'test',
                   'EVENTS': 'queue-events', 'TICKETS': 'queue-tickets', 'JWT_SECRET': JWT_SECRET,
                   'QUEUE_TOKEN_SECRET': QUEUE_SECRET, 'ADMIT_PER_SECOND': '10'})

import admitter  # noqa: E402
import waiting_room  # noqa: E402


def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()


def access_token(user, secret=JWT_SECRET, alg='HS512', use='access', exp_in=600):
    digest = {'HS256': hashlib.sha256, 'HS512': hashlib.sha512}[alg]
    head = b64(json.dumps({'alg': alg}).encode())
    body = b64(json.dumps({'sub': str(user), 'token_use': use, 'role': 'USER', 'exp': int(time.time()) + exp_in}).encode())
    return f'{head}.{body}.{b64(hmac.new(secret.encode(), f"{head}.{body}".encode(), digest).digest())}'


def call(method, user, event='107', token=None):
    headers = {'authorization': f'Bearer {token or access_token(user)}'} if user is not None else {}
    response = waiting_room.handler({'requestContext': {'http': {'method': method}}, 'pathParameters': {'eventId': event},
                                     'headers': headers}, None)
    return response['statusCode'], json.loads(response['body'])


@pytest.fixture(autouse=True)
def dynamodb():
    with mock_aws():
        db = boto3.resource('dynamodb')
        for name in ('queue-events', 'queue-tickets'):
            db.create_table(TableName=name, KeySchema=[{'AttributeName': 'id', 'KeyType': 'HASH'}],
                            AttributeDefinitions=[{'AttributeName': 'id', 'AttributeType': 'S'}], BillingMode='PAY_PER_REQUEST')
        waiting_room._db.clear()
        admitter._events = None
        yield


def test_users_get_numbers_in_arrival_order_and_keep_them():
    positions = [call('POST', user)[1]['data']['position'] for user in range(1, 6)]
    assert positions == [1, 2, 3, 4, 5]
    assert call('POST', 3)[1]['data']['position'] == 3
    assert call('GET', 5)[1]['data']['position'] == 5


def test_concurrent_joins_never_share_a_number():
    results = []

    def join(user):
        results.append(call('POST', user)[1]['data']['position'])

    threads = [threading.Thread(target=join, args=(user,)) for user in range(1, 51)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(results) == list(range(1, 51))


def test_admitter_lets_n_in_per_second_and_only_once_per_second():
    for user in range(1, 26):
        call('POST', user)
    assert admitter.step(1000) == 10
    assert admitter.step(1000) == 0  # a second run in the same second
    assert call('GET', 10)[1]['data']['admitted'] is True
    assert call('GET', 11)[1]['data']['position'] == 1
    assert admitter.step(1001) == 10
    assert admitter.step(1002) == 5
    assert admitter.step(1003) == 0


def test_admission_never_runs_ahead_of_people_who_joined():
    call('POST', 1)
    for second in range(1000, 1010):
        admitter.step(second)
    # Ten idle seconds must not open the door for a crowd arriving afterwards.
    for user in range(2, 32):
        call('POST', user)
    admitter.step(1010)
    admitted = [call('GET', user)[1]['data']['admitted'] for user in range(2, 32)]
    assert admitted.count(True) == 10


def test_admitted_user_gets_a_queue_token_for_this_user_and_event():
    call('POST', 7)
    admitter.step(1000)
    status, body = call('GET', 7)
    data = body['data']
    assert status == 200 and data['admitted'] is True and 0 < data['passExpiresInSeconds'] <= 300
    head, payload, sig = data['token'].split('.')
    expected = hmac.new(QUEUE_SECRET.encode(), f'{head}.{payload}'.encode(), hashlib.sha256).digest()
    assert b64(expected) == sig
    claims = json.loads(base64.urlsafe_b64decode(payload + '=='))
    assert claims['sub'] == '7' and claims['eid'] == 107


def test_pass_does_not_grow_by_polling_again():
    call('POST', 7)
    admitter.step(1000)
    first = call('GET', 7)[1]['data']['token']
    second = call('GET', 7)[1]['data']['token']
    exp = lambda token: json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '=='))['exp']
    assert exp(first) == exp(second)


def test_expired_pass_must_queue_again():
    call('POST', 7)
    admitter.step(1000)
    boto3.resource('dynamodb').Table('queue-tickets').update_item(
        Key={'id': '107#7'}, UpdateExpression='SET #a = :old', ExpressionAttributeNames={'#a': 'at'},
        ExpressionAttributeValues={':old': int(time.time()) - 301})
    status, body = call('GET', 7)
    assert status == 409 and body['code'] == 'QUEUE_PASS_EXPIRED'
    call('POST', 8)
    status, body = call('POST', 7)  # joins again at the back, behind user 8
    assert status == 200 and body['data']['admitted'] is False and body['data']['position'] == 2


def test_requests_without_a_valid_service_token_are_refused():
    assert call('POST', None)[0] == 401
    assert call('POST', 1, token=access_token(1, secret='b' * 64))[0] == 401
    assert call('POST', 1, token=access_token(1, use='refresh'))[0] == 401
    assert call('POST', 1, token=access_token(1, exp_in=-1))[0] == 401
    assert call('POST', 1, token=access_token(1, alg='HS256'))[0] == 200


def test_status_before_joining_and_bad_event_ids():
    assert call('GET', 1)[1]['code'] == 'QUEUE_NOT_JOINED'
    assert call('POST', 1, event='abc')[0] == 404


def test_poll_interval_grows_toward_the_back_of_the_line():
    for user in range(1, 401):
        call('POST', user)
    front = call('GET', 1)[1]['data']
    back = call('GET', 400)[1]['data']
    assert front['nextPollSeconds'] == 2
    assert back['estimatedWaitSeconds'] == 40 and back['nextPollSeconds'] == 20
