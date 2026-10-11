#!/usr/bin/env python3
"""R6.3 follow-up (2026-10-12): make the VSIX byte-reproducible.

vsce writes each zip entry with the source file's mtime, so
two clean builds within the same minute still differ in
sub-second precision. This script post-processes the .vsix
to pin every entry's mtime to SOURCE_DATE_EPOCH (default
1700000000 = 2023-11-14T22:13:20Z), producing a reproducible
artifact that any two builds with the same env agree on.

Usage: python3 scripts/reproducible-vsix.py <vsix> [epoch-seconds]
"""

import os
import shutil
import sys
import tempfile
import zipfile


def main() -> int:
    if len(sys.argv) < 2:
        print(
            "usage: python3 scripts/reproducible-vsix.py <vsix> [epoch-seconds]",
            file=sys.stderr,
        )
        return 2
    vsix = os.path.abspath(sys.argv[1])
    epoch = int(sys.argv[2]) if len(sys.argv) > 2 else 1700000000
    dos = dos_time(epoch)

    src = zipfile.ZipFile(vsix, "r")
    entries = src.infolist()
    # Write to a sibling tmp file (same dir, so the rename is
    # atomic on the same filesystem) then move over.
    fd, tmp = tempfile.mkstemp(prefix=".vsix-repro-", dir=os.path.dirname(vsix))
    os.close(fd)
    out = zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9)
    for info in entries:
        data = src.read(info.filename)
        new_info = zipfile.ZipInfo(filename=info.filename, date_time=dos)
        # Preserve the source compress_type (DEFLATE for vsce
        # packages; DEFLATE level 9 here matches vsce's default
        # so the bytes are deterministic).
        new_info.compress_type = info.compress_type
        new_info.create_system = 3  # Unix
        new_info.external_attr = (0o644 & 0xFFFF) << 16
        out.writestr(new_info, data)
    src.close()
    out.close()
    shutil.move(tmp, vsix)
    print(
        f"reproducible-vsix: {vsix} rewritten with mtime "
        f"{iso(epoch)} ({len(entries)} entries)"
    )
    return 0


def dos_time(epoch_seconds: int):
    # Per PKWARE APPNOTE.TXT §4.4.10 — date_time is a single
    # 6-tuple (year, month, day, hour, minute, second/2).
    from datetime import datetime, timezone
    d = datetime.fromtimestamp(epoch_seconds, tz=timezone.utc)
    sec = d.second // 2
    return (d.year, d.month, d.day, d.hour, d.minute, sec)


def iso(epoch_seconds: int) -> str:
    from datetime import datetime, timezone
    return datetime.fromtimestamp(epoch_seconds, tz=timezone.utc).isoformat()


if __name__ == "__main__":
    sys.exit(main())
