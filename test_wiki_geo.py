import urllib.request
import json

def fetch_wiki_geo(lat, lon):
    url = f'https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsradius=10000&gscoord={lat}|{lon}&gslimit=10&format=json'
    req = urllib.request.Request(url, headers={'User-Agent': 'TripMateApp/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            data = json.loads(response.read().decode())
            results = data.get('query', {}).get('geosearch', [])
            for r in results:
                print(r['title'])
    except Exception as e:
        print(e)

print("Mysuru:")
fetch_wiki_geo(12.295, 76.639) # Mysuru
print("\nParis:")
fetch_wiki_geo(48.8566, 2.3522)
