#!/bin/bash
set -euo pipefail

# Compress generated blog images for web use
# Usage: ./scripts/compress-images.sh <directory>
#
# Converts all PNG/JPG images in the given directory to:
#   - Max width 1200px (preserving aspect ratio)
#   - JPEG format at 80% quality (~100-300KB per image)
#   - Originals backed up to <directory>/originals/

DIR="${1:?Usage: $0 <directory-of-images>}"

if [ ! -d "$DIR" ]; then
  echo "Error: Directory '$DIR' does not exist"
  exit 1
fi

BACKUP_DIR="$DIR/originals"
mkdir -p "$BACKUP_DIR"

count=0
for img in "$DIR"/*.{png,PNG,jpg,JPG,jpeg,JPEG,webp,WEBP} ; do
  [ -f "$img" ] || continue

  filename=$(basename "$img")
  name="${filename%.*}"
  ext="${filename##*.}"

  # Backup original
  cp "$img" "$BACKUP_DIR/$filename"

  # Get current width
  width=$(sips -g pixelWidth "$img" | tail -1 | awk '{print $2}')

  # Resize if wider than 1200px
  if [ "$width" -gt 1200 ]; then
    sips --resampleWidth 1200 "$img" --out "$img" > /dev/null 2>&1
    echo "  Resized: $filename ($width -> 1200px wide)"
  fi

  # Convert to JPEG at 80% quality (unless already a small JPEG)
  output="$DIR/${name}.jpg"
  sips -s format jpeg -s formatOptions 80 "$img" --out "$output" > /dev/null 2>&1

  # Remove original if it was a different format
  if [ "$ext" != "jpg" ] && [ "$ext" != "JPG" ] && [ "$ext" != "jpeg" ] && [ "$ext" != "JPEG" ]; then
    rm "$img"
  fi

  original_size=$(stat -f%z "$BACKUP_DIR/$filename")
  new_size=$(stat -f%z "$output")
  echo "  $filename: $(( original_size / 1024 ))KB -> $(( new_size / 1024 ))KB"
  count=$((count + 1))
done

echo ""
echo "Done. Compressed $count images."
echo "Originals saved in: $BACKUP_DIR"
