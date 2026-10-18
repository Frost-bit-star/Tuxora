#!/usr/bin/env python3
"""
Package the original Tuxora dotfiles as a single asset consumed by the app.

The source tree ("files/") is the *contents* of $HOME. Android assets cannot
represent symbolic links, so the tree is split in two:

  * tuxora-home.zip            - every regular file/dir, relative to $HOME
  * tuxora-home-symlinks.txt   - one `<target>←<path>` line per symlink
                                 (same separator style Termux uses)

TuxoraSetup recreates the symlinks with android.system.Os.symlink() after
extracting the zip.

Usage:
  scripts/make-tuxora-home.py <dotfiles-dir> <out-assets-dir>
"""

import os
import stat
import sys
import zipfile

SEP = "\u2190"  # ←


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, out = sys.argv[1], sys.argv[2]
    src = os.path.abspath(src)
    os.makedirs(out, exist_ok=True)
    zip_path = os.path.join(out, "tuxora-home.zip")
    links_path = os.path.join(out, "tuxora-home-symlinks.txt")

    n_files = n_dirs = n_links = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf, \
            open(links_path, "w", encoding="utf-8") as links:
        for root, dirs, files in os.walk(src, followlinks=False):
            rel_root = os.path.relpath(root, src)
            # Treat symlinked directories as symlinks (do not descend) and
            # record them in the manifest alongside file symlinks.
            real_dirs = []
            for d in dirs:
                full_d = os.path.join(root, d)
                rel_d = os.path.normpath(os.path.join(rel_root, d))
                arc_d = rel_d.replace(os.sep, "/")
                if os.path.islink(full_d):
                    links.write("%s%s%s\n" % (os.readlink(full_d), SEP, arc_d))
                    n_links += 1
                else:
                    real_dirs.append(d)
                    if rel_d != ".":
                        zf.writestr(arc_d + "/", "")
                        n_dirs += 1
            dirs[:] = real_dirs
            for name in files:
                full = os.path.join(root, name)
                rel = os.path.normpath(os.path.join(rel_root, name))
                arc = rel.replace(os.sep, "/")
                if os.path.islink(full):
                    links.write("%s%s%s\n" % (os.readlink(full), SEP, arc))
                    n_links += 1
                elif os.path.isfile(full):
                    zf.write(full, arc)
                    n_files += 1

    print("wrote %s (%d files, %d dirs)" % (zip_path, n_files, n_dirs))
    print("wrote %s (%d symlinks)" % (links_path, n_links))
    print("zip size: %.1f MB" % (os.path.getsize(zip_path) / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
