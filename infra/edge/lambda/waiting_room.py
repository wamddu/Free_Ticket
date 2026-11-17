"""Edge waiting room API (POST/GET /api/queue/{eventId}) behind CloudFront.
Same contract as the server's queue: number by arrival, admitted when number <= event's admitted count.
Admitted users get a 5-minute JWT the order API checks. Never touches the Lightsail server."""
import base64, hashlib, hmac, json, math, os, time
import boto3
from botocore.exceptions import ClientError

RATE = int(os.environ.get('ADMIT_PER_SECOND', '20'))
PASS_SECONDS = 300
ALGS = {'HS256': hashlib.sha256, 'HS384': hashlib.sha384, 'HS512': hashlib.sha512}
_db = {}


def table(name):
    if name not in _db:
        _db[name] = boto3.resource('dynamodb').Table(os.environ[name])
    return _db[name]


def b64d(part):
    return base64.urlsafe_b64decode(part + '=' * (-len(part) % 4))


def b64e(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()


def user_id(authorization):
    """The service's own access token, checked with the same key the server signs it with."""
    try:
        head, body, sig = authorization.split(' ', 1)[1].split('.')
        digest = ALGS[json.loads(b64d(head))['alg']]
        expected = hmac.new(os.environ['JWT_SECRET'].encode(), f'{head}.{body}'.encode(), digest).digest()
        claims = json.loads(b64d(body))
        if hmac.compare_digest(expected, b64d(sig)) and claims.get('token_use') == 'access' and claims['exp'] > time.time():
            return int(claims['sub'])
    except (AttributeError, IndexError, KeyError, ValueError, TypeError):
        pass
    return None


def queue_token(user, event, expires):
    head = b64e(b'{"alg":"HS256","typ":"JWT"}')
    body = b64e(json.dumps({'sub': str(user), 'eid': event, 'exp': expires}, separators=(',', ':')).encode())
    sig = hmac.new(os.environ['QUEUE_TOKEN_SECRET'].encode(), f'{head}.{body}'.encode(), hashlib.sha256).digest()
    return f'{head}.{body}.{b64e(sig)}'


def join(event, user):
    """One number per user and event; joining again keeps it."""
    tickets = table('TICKETS')
    key = {'id': f'{event}#{user}'}
    found = tickets.get_item(Key=key).get('Item')
    if found:
        return found
    number = table('EVENTS').update_item(Key={'id': str(event)}, UpdateExpression='ADD issued :one',
                                         ExpressionAttributeValues={':one': 1}, ReturnValues='UPDATED_NEW')['Attributes']['issued']
    item = {**key, 'n': number, 'ttl': int(time.time()) + 86400}
    try:
        tickets.put_item(Item=item, ConditionExpression='attribute_not_exists(id)')
        return item
    except ClientError as e:
        if e.response['Error']['Code'] != 'ConditionalCheckFailedException':
            raise
        return tickets.get_item(Key=key, ConsistentRead=True)['Item']


def status(event, user, ticket):
    admitted = table('EVENTS').get_item(Key={'id': str(event)}).get('Item', {}).get('admitted', 0)
    number = int(ticket['n'])
    if number <= admitted:
        now = int(time.time())
        at = int(ticket.get('at', 0))
        if not at:
            at = int(table('TICKETS').update_item(Key={'id': ticket['id']}, UpdateExpression='SET #a = if_not_exists(#a, :now)',
                                                  ExpressionAttributeNames={'#a': 'at'}, ExpressionAttributeValues={':now': now},
                                                  ReturnValues='UPDATED_NEW')['Attributes']['at'])
        left = at + PASS_SECONDS - now
        if left <= 0:
            return None, 'QUEUE_PASS_EXPIRED'
        return {'admitted': True, 'bypassed': False, 'position': None, 'estimatedWaitSeconds': None,
                'nextPollSeconds': None, 'passExpiresInSeconds': left, 'token': queue_token(user, event, at + PASS_SECONDS)}, None
    position = number - int(admitted)
    wait = math.ceil(position / RATE)
    return {'admitted': False, 'bypassed': False, 'position': position, 'estimatedWaitSeconds': wait,
            'nextPollSeconds': max(2, min(30, (wait + 1) // 2)), 'passExpiresInSeconds': None, 'token': None}, None


def reply(code, body):
    return {'statusCode': code, 'headers': {'content-type': 'application/json', 'cache-control': 'no-store'},
            'body': json.dumps(body, ensure_ascii=False)}


def handler(request, _context):
    event = (request.get('pathParameters') or {}).get('eventId', '')
    if not event.isdigit():
        return reply(404, {'code': 'EVENT_NOT_FOUND', 'message': '공연을 찾을 수 없습니다.'})
    event = int(event)
    user = user_id((request.get('headers') or {}).get('authorization', ''))
    if user is None:
        return reply(401, {'code': 'UNAUTHORIZED', 'message': '로그인이 필요합니다.'})
    if request['requestContext']['http']['method'] == 'POST':
        ticket = join(event, user)
    else:
        ticket = table('TICKETS').get_item(Key={'id': f'{event}#{user}'}).get('Item')
        if not ticket:
            return reply(404, {'code': 'QUEUE_NOT_JOINED', 'message': '대기열에 등록되어 있지 않습니다.'})
    data, error = status(event, user, ticket)
    if error and request['requestContext']['http']['method'] == 'POST':
        # The pass ran out before ordering: joining again goes to the back of the line with a new number.
        table('TICKETS').delete_item(Key={'id': ticket['id']})
        data, error = status(event, user, join(event, user))
    if error:
        return reply(409, {'code': error, 'message': '입장 시간이 지났습니다. 다시 줄을 서 주세요.'})
    return reply(200, {'message': '대기 순서를 조회했습니다.', 'data': data})
