# Product Ranking Template

## Development Notes

### import sample data

```
$ python data/import_eventserver.py --access_key
```

### query

normal:

```
curl -H "Content-Type: application/json" \
-d '{ "user": "u2", "items": ["i1", "i3", "i10", "i2", "i5", "i31", "i9"]}' \
http://localhost:8000/queries.json \
-w %{time_connect}:%{time_starttransfer}:%{time_total}
```

unknown user:

```
curl -H "Content-Type: application/json" \
-d '{ "user": "unknown_user", "items": ["i1", "i3", "i10", "i2", "i5", "i31", "i9"]}' \
http://localhost:8000/queries.json \
-w %{time_connect}:%{time_starttransfer}:%{time_total}
```

unknown item:

```
curl -H "Content-Type: application/json" \
-d '{ "user": "u3", "items": ["unk1", "i3", "i10", "i2", "i9"]}' \
http://localhost:8000/queries.json \
-w %{time_connect}:%{time_starttransfer}:%{time_total}
```

all unknown items:

```
curl -H "Content-Type: application/json" \
-d '{ "user": "u4", "items": ["unk1", "unk2", "unk3", "unk4"]}' \
http://localhost:8000/queries.json \
-w %{time_connect}:%{time_starttransfer}:%{time_total}
```