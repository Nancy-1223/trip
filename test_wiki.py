import urllib.request
import urllib.parse
import json
import re

def fetch_wiki(query):
    # Searching for "tourist attractions in {location}"
    url = f'https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={urllib.parse.quote("tourist attractions in " + query)}&utf8=&format=json'
    req = urllib.request.Request(url, headers={'User-Agent': 'TripMateApp/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            data = json.loads(response.read().decode())
            results = data.get('query', {}).get('search', [])
            places = []
            for r in results:
                title = r['title']
                if 'List of' not in title and 'Tourism in' not in title and query.lower() not in title.lower():
                    # We can clean up if necessary, but returning the title is often good.
                    places.append(title)
            
            # If not enough, try another query
            if len(places) < 3:
                url = f'https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={urllib.parse.quote("landmarks in " + query)}&utf8=&format=json'
                req = urllib.request.Request(url, headers={'User-Agent': 'TripMateApp/1.0'})
                with urllib.request.urlopen(req, timeout=5) as response:
                    data = json.loads(response.read().decode())
                    results = data.get('query', {}).get('search', [])
                    for r in results:
                        title = r['title']
                        if 'List of' not in title and 'Tourism in' not in title and query.lower() not in title.lower() and title not in places:
                            places.append(title)
            print("Places:", places[:5])
    except Exception as e:
        print(e)

fetch_wiki('Mysuru')
fetch_wiki('Paris')
fetch_wiki('London')
