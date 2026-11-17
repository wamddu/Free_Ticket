"""Moves every event's admitted count up by ADMIT_PER_SECOND each second, never past the numbers handed out.
EventBridge starts it every minute and it runs for that minute. Runs may overlap at the edges, so each second is
claimed with a condition on the event item (sec < now): a second can raise the count only once."""
import os, time
import boto3
from botocore.exceptions import ClientError

RATE = int(os.environ.get('ADMIT_PER_SECOND', '20'))
_events = None


def events():
    global _events
    if _events is None:
        _events = boto3.resource('dynamodb').Table(os.environ['EVENTS'])
    return _events


def step(second):
    moved = 0
    for item in events().scan().get('Items', []):
        issued, admitted = int(item.get('issued', 0)), int(item.get('admitted', 0))
        if admitted >= issued:
            continue
        new = min(issued, admitted + RATE)
        try:
            events().update_item(Key={'id': item['id']}, UpdateExpression='SET admitted = :new, sec = :s',
                                 ConditionExpression='(attribute_not_exists(admitted) OR admitted = :old) AND '
                                                     '(attribute_not_exists(sec) OR sec < :s)',
                                 ExpressionAttributeValues={':new': new, ':old': admitted, ':s': second})
            moved += new - admitted
        except ClientError as e:
            if e.response['Error']['Code'] != 'ConditionalCheckFailedException':
                raise
    return moved


def handler(_event, context):
    while context.get_remaining_time_in_millis() > 2000:
        second = int(time.time())
        step(second)
        time.sleep(max(0.0, second + 1 - time.time()))
