#!/usr/bin/env python3
"""Build the app's poke database from ZX Pokemaster's.

    scripts/build-poke-db.py ~/apps/ZX_Pokemaster-.../pokemaster.db

Writes app/src/main/assets/pokes.db, which the app copies out on first use.

Pokemaster's database is 20 MB and mostly things an emulator has no use for -
genres, authors, publishers, release dates, every file of every game whether it
has cheats or not. Two things are needed: the pokes, and enough of a fingerprint
to know which game is loaded. So this keeps

  game   the games that actually have pokes, with the .pok text as it stands
  file   one row per known dump of those games: md5 -> game
  meta   where the data came from, and how much of it there is

and nothing else. It is a script rather than a one-off because the source is
updated from time to time and because a binary asset nobody can rebuild is a
binary asset nobody can check.

The .pok text is kept verbatim rather than parsed into tables. It is a compact,
stable, documented format - N names a trainer, M and Z are its pokes, Z being the
last - and parsing it in the app costs twenty lines, where a normalised schema
would cost three tables and a join for no gain.
"""
import os
import sqlite3
import sys
import time

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, "app", "src", "main", "assets", "pokes.db")

SOURCE = ("Pokes from The Tipshop (the-tipshop.co.uk, run by Gerard Sweeney), "
          "collected as AllTipshopPokes by Lady Eklipse and distributed with "
          "ZX Pokemaster. File fingerprints from ZXDB.")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)

    source = sys.argv[1]
    if not os.path.exists(source):
        sys.exit(f"no such database: {source}")

    src = sqlite3.connect(f"file:{source}?mode=ro", uri=True)

    if os.path.exists(OUT):
        os.remove(OUT)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)

    out = sqlite3.connect(OUT)
    out.executescript("""
        create table game (
          id     integer primary key,   -- ZXDB's id, so a row can be traced back
          name   text not null,
          pokes  text not null          -- .pok format, verbatim
        );
        -- Without rowid: with one, the hash is stored twice over - once in the
        -- table and once in the index that makes it a primary key - and that is
        -- half a megabyte for nothing on thirty four thousand rows.
        create table file (
          md5    blob primary key,      -- sixteen raw bytes, not hex: half the size
          game   integer not null
        ) without rowid;
        create table meta ( key text primary key, value text not null );
    """)

    games = src.execute("""
        select zxdb_id, name, pok_file_contents from game
        where pok_file_contents is not null and length(pok_file_contents) > 0
    """).fetchall()

    out.executemany("insert into game values (?,?,?)", [
        (zxdb_id,
         name or "?",
         blob.decode("utf-8", "replace") if isinstance(blob, bytes) else blob)
        for zxdb_id, name, blob in games
    ])

    # Every known dump of those games. Duplicated hashes do happen - the same
    # file listed under two releases - and the first one wins, since both point
    # at the same pokes anyway.
    seen = set()
    rows = []
    for md5, game in src.execute("""
            select f.md5, f.game_zxdb_id from game_file f
            join game g on g.zxdb_id = f.game_zxdb_id
            where length(g.pok_file_contents) > 0 and length(f.md5) = 32
    """):
        raw = bytes.fromhex(md5)
        if raw in seen:
            continue
        seen.add(raw)
        rows.append((raw, game))

    out.executemany("insert into file values (?,?)", rows)

    pokes = sum(1 for _, _, blob in games
                for line in (blob.decode("utf-8", "replace")
                             if isinstance(blob, bytes) else blob).splitlines()
                if line[:1] in ("M", "Z"))
    trainers = sum(1 for _, _, blob in games
                   for line in (blob.decode("utf-8", "replace")
                                if isinstance(blob, bytes) else blob).splitlines()
                   if line[:1] == "N")

    out.executemany("insert into meta values (?,?)", [
        ("source", SOURCE),
        ("built", time.strftime("%Y-%m-%d")),
        ("games", str(len(games))),
        ("trainers", str(trainers)),
        ("pokes", str(pokes)),
        ("files", str(len(rows))),
    ])

    out.commit()
    out.execute("vacuum")
    out.close()

    print(f"{OUT}: {os.path.getsize(OUT) / 1024:.0f} kB "
          f"({len(games)} games, {trainers} trainers, {pokes} pokes, "
          f"{len(rows)} file hashes)")


if __name__ == "__main__":
    main()
