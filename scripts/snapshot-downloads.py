#!/usr/bin/env python3
"""Records what GitHub says each release asset has been downloaded, once a day.

GitHub keeps a running total per asset and no history at all, so the number that
matters - how many downloads happened *today* - can only be had by writing the
total down and subtracting. That is all this does: one row per asset per day,
appended to a CSV, which docs/stats/index.html then reads and turns into daily
figures.

    scripts/snapshot-downloads.py --print         # show the totals, write nothing
    scripts/snapshot-downloads.py --file some.csv # append today's to that file

Run by .github/workflows/stats.yml on a schedule, which keeps the CSV on a branch
of its own called `stats` rather than on main - a commit a day of data has no
business in the history of the app. Safe to run twice: a day already recorded is
replaced rather than duplicated, so a manual run after a scheduled one corrects it.

What the numbers mean, since they are not obvious:

  Zedex-<v>.apk.sha256   the updater fetches this for its *own* version at every
                         start, so the daily rise is roughly how many times that
                         version was started. Summed across versions, the app.
  Zedex-<v>.apk          downloads of the build itself: someone taking an update,
                         or someone finding the release page.

Both include your own testing and any CI, and GitHub's totals lag by minutes, so
treat single figures as noise and look at the shape.
"""

import argparse
import csv
import datetime
import json
import pathlib
import sys
import urllib.request

REPO = "dimitriuz/zedex"
API = f"https://api.github.com/repos/{REPO}/releases?per_page=100"

FIELDS = ["date", "tag", "asset", "downloads"]


def fetch():
    """Every published release's assets, as (tag, asset, count)."""
    request = urllib.request.Request(
        API, headers={"Accept": "application/vnd.github+json",
                      "User-Agent": "zedex-stats"})

    with urllib.request.urlopen(request, timeout=30) as response:
        releases = json.load(response)

    rows = []
    for release in releases:
        # A draft is not published, and its assets are not reachable by anyone
        # but the owner, so counting them would be counting nothing.
        if release.get("draft"):
            continue

        for asset in release.get("assets", []):
            rows.append((release["tag_name"], asset["name"],
                         asset["download_count"]))

    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--print", dest="show", action="store_true",
                        help="show the totals and write nothing")
    parser.add_argument("--date", default=datetime.date.today().isoformat(),
                        help="the day to record as (default: today)")
    parser.add_argument("--file", default="downloads.csv", type=pathlib.Path,
                        help="the CSV to append to (default: ./downloads.csv)")
    options = parser.parse_args()

    csv_path = options.file

    try:
        totals = fetch()
    except Exception as problem:                       # noqa: BLE001
        print(f"error: cannot read the releases API: {problem}", file=sys.stderr)
        return 1

    if not totals:
        print("error: no published releases with assets", file=sys.stderr)
        return 1

    if options.show:
        for tag, asset, count in totals:
            print(f"{tag:<10} {asset:<28} {count:>6}")
        return 0

    # Everything already recorded, minus any earlier attempt at today, so a
    # second run in a day corrects it instead of doubling it.
    kept = []
    if csv_path.exists():
        with csv_path.open(newline="") as handle:
            kept = [row for row in csv.DictReader(handle)
                    if row["date"] != options.date]

    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as handle:
        # LF and not the csv module's default CRLF. The page splits on newlines,
        # and a trailing \r rode along on the last column - which made its key
        # "downloads\r", so every figure came out NaN and the page drew nothing.
        writer = csv.DictWriter(handle, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()

        for row in kept:
            writer.writerow(row)

        for tag, asset, count in totals:
            writer.writerow({"date": options.date, "tag": tag,
                             "asset": asset, "downloads": count})

    print(f"{options.date}: {len(totals)} assets recorded, "
          f"{len(kept) + len(totals)} rows in {csv_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
