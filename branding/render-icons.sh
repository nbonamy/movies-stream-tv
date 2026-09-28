#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
res="$root/app/src/main/res"

for item in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density=${item%:*}
  size=${item#*:}
  mkdir -p "$res/mipmap-$density"
  rsvg-convert --width "$size" --height "$size" "$root/branding/movies-icon.svg" > "$res/mipmap-$density/ic_launcher.png"
done

rsvg-convert --width 320 --height 180 "$root/branding/movies-banner.svg" > "$res/drawable-xhdpi/tv_banner.png"
