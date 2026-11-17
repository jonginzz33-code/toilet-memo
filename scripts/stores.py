"""소상공인 상권정보(분기마다 갱신)로 '우리 동네' 가게 파일을 만든다.

- 업종: 카페·빵집·음식점·편의점·약국·안경점·꽃집
- 0.2도(약 20km) 칸마다 파일 하나: tile_<lat>_<lon>.json.gz  (앱은 내 주변 칸만 받음)
- 지난번 전체 목록(stores-all.json.gz)과 비교해 새로 생긴 가게·사라진 가게를 칸 파일에 넣음
"""
import gzip, json, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

KEY = os.environ.get("DATA_GO_KR_KEY") or "fa4fc859cd80c582d1cbf58943b918023182ed5d6e5f8d2f00b40dfdbcc0e9da"
API = "https://apis.data.go.kr/B553077/api/open/sdsc2/"
PREV = "https://github.com/jonginzz33-code/toilet-memo/releases/download/store-data/stores-all.json.gz"
TILE = 0.2
# (요청 조건, 앱에서 쓰는 종류)
QUERIES = [("indsLclsCd=I2", None), ("indsSclsCd=G20405", "conv"), ("indsSclsCd=G21501", "pharm"),
           ("indsSclsCd=G21602", "glasses"), ("indsSclsCd=G21901", "flower")]


def get(path):
    for i in range(6):
        try:
            with urllib.request.urlopen(f"{API}{path}&serviceKey={KEY}&type=json", timeout=120) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            print("retry", path[:80], e, flush=True)
            time.sleep(3 + i * 3)
    raise SystemExit("failed: " + path[:120])


def kind(it, fixed):
    if fixed:
        return fixed
    s = it.get("indsSclsCd", "")
    if s == "I21201": return "cafe"
    if s == "I21001": return "bakery"
    return "food"


def tile_of(lat, lon):
    return f"{int(lat // TILE)}_{int(lon // TILE)}"


def fetch_all():
    megas = [m["ctprvnCd"] for m in get("baroApi?resId=dong&catId=mega")["body"]["items"]]
    jobs, stdr = [], None
    for m in megas:
        for q, fixed in QUERIES:
            d = get(f"storeListInDong?divId=ctprvnCd&key={m}&{q}&pageNo=1&numOfRows=1")
            stdr = stdr or d["header"].get("stdrYm")
            n = int(d["body"].get("totalCount") or 0)
            jobs += [(m, q, fixed, p) for p in range(1, n // 1000 + 2)]
    print("stdrYm", stdr, "calls", len(jobs), flush=True)
    stores = {}

    def one(j):
        m, q, fixed, p = j
        d = get(f"storeListInDong?divId=ctprvnCd&key={m}&{q}&pageNo={p}&numOfRows=1000")
        return fixed, d.get("body", {}).get("items") or []

    done = 0
    with ThreadPoolExecutor(8) as ex:
        for fixed, items in ex.map(one, jobs):
            for it in items:
                try:
                    lat, lon = round(float(it["lat"]), 6), round(float(it["lon"]), 6)
                except Exception:
                    continue
                name = (it.get("bizesNm") or "").strip() + (" " + it["brchNm"].strip() if it.get("brchNm") else "")
                stores[it["bizesId"]] = [it["bizesId"], name, kind(it, fixed), it.get("indsSclsNm", ""), lat, lon,
                                         it.get("rdnmAdr") or it.get("lnoAdr") or ""]
            done += 1
            if done % 200 == 0:
                print("pages", done, "/", len(jobs), "stores", len(stores), flush=True)
    return stdr, stores


def load_prev():
    try:
        with urllib.request.urlopen(PREV, timeout=120) as r:
            return json.loads(gzip.decompress(r.read()).decode("utf-8"))
    except Exception as e:
        print("no previous:", e)
        return None


def diff(prev, stdr, stores):
    """지난 분기와 비교. 기준 달이 같으면 지난번 소식을 그대로 둠"""
    if not prev:
        return {}, {}, None
    if prev.get("stdrYm") == stdr:
        return prev.get("new", {}), prev.get("gone", {}), prev.get("since")
    old = prev["stores"]
    new = {k: stdr for k in stores if k not in old}
    gone = {k: old[k] for k in old if k not in stores}
    return new, gone, prev.get("stdrYm")


def main(outdir):
    stdr, stores = fetch_all()
    if len(stores) < 100000:
        raise SystemExit(f"너무 적어요 ({len(stores)}). 저장 안 함")
    prev = load_prev()
    new, gone, since = diff(prev, stdr, stores)
    print("stores", len(stores), "new", len(new), "gone", len(gone), "since", since)
    os.makedirs(outdir, exist_ok=True)
    tiles = {}
    for k, s in stores.items():
        t = tiles.setdefault(tile_of(s[4], s[5]), {"stores": [], "new": [], "gone": []})
        t["stores"].append(s)
        if k in new:
            t["new"].append(k)
    for k, s in gone.items():
        tiles.setdefault(tile_of(s[4], s[5]), {"stores": [], "new": [], "gone": []})["gone"].append(s)
    for name, t in tiles.items():
        t.update(stdrYm=stdr, since=since)
        with gzip.open(os.path.join(outdir, f"tile_{name}.json.gz"), "wt", encoding="utf-8") as f:
            json.dump(t, f, ensure_ascii=False, separators=(",", ":"))
    with gzip.open(os.path.join(outdir, "stores-all.json.gz"), "wt", encoding="utf-8") as f:
        json.dump({"stdrYm": stdr, "since": since, "stores": stores, "new": new, "gone": gone}, f,
                  ensure_ascii=False, separators=(",", ":"))
    print("tiles", len(tiles))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "store-out")
