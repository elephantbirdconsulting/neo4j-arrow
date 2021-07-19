import pyarrow as pa
import pyarrow.flight as flight
import sys
import base64

pa.enable_signal_handlers(True)

location = ("192.168.1.42", 9999)
client = flight.FlightClient(location)
print(f"Trying to connect to location {location}")

try:
    client.wait_for_available(5)
    print(f"Connected")
except Exception as e:
    if type(e) is not flight.FlightUnauthenticatedError:
        print(f"⁉ Failed to connect to {location}: {e.args}")
        sys.exit(1)
    else:
        print("Server requires auth, but connection possible")

options = flight.FlightCallOptions(headers=[
    (b'authorization', b'Basic ' + base64.b64encode(b'neo4j:password'))
])

actions = list(client.list_actions(options=options))
if len(actions) == 0:
    print("Found zero actions 😕")
else:
    print(f"💥 Found {len(actions)} actions!")
    for action in actions:
        print(f"action {action}")

schema = pa.schema([('n', pa.string())])
action = ("cypherRead", "UNWIND range(1, 23) AS n RETURN n".encode('utf8'))
try:
    for row in client.do_action(action, options=options):
        print(f"row: {row.body.to_pybytes()}")
except Exception as e:
    print(f"⚠ {e}")
    sys.exit(1)

flights = list(client.list_flights(options=options))
if len(flights) == 0:
    print("Found zero flights 😕")
else:
    print(f"Found {len(flights)} flights")
    for flight in flights:
        ticket = flight.endpoints[0].ticket
        print(f"flight: [cmd={flight.descriptor.command}, ticket={ticket}")
        result = client.do_get(ticket, options=options)
        for chunk, metadata in result:
            print(f"chunk: {chunk}, metadata: {metadata}")
            #print(f"num rows: {chunk.num_rows}")
            for col in chunk:
                print(f"col: {col}")
