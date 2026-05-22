import urllib.request
import urllib.parse
import json

def fetch_overpass(location):
    query = f'[out:json][timeout:10];area[name="{location}"]->.searchArea;node["tourism"="attraction"](area.searchArea);out 5;'
    url = 'https://overpass-api.de/api/interpreter?data=' + urllib.parse.quote(query)
    req = urllib.request.Request(url, headers={'User-Agent': 'TripMateApp/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            data = json.loads(response.read().decode())
            for e in data.get('elements', []):
                print(e.get('tags', {}).get('name', 'Unknown'))
    except Exception as e:
        print(e)

fetch_overpass('Mysuru')
