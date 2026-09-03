#!/usr/bin/env bash
# adb_full_backup_macos.sh
# File-by-file Android backup to macOS with filename sanitization

set -euo pipefail

adb shell "find / -type f" > files.txt

# Shuffle the file list to randomize processing order
shuf files.txt > files_shuffled.txt

while IFS= read -r f; do
    # Remove trailing return character
    f="${f//$'\r'/}"

    # Remove leading slash for local path creation
    file_path="${f#/}"

    if [[ -z "$file_path" || "$file_path" == dev/* || "$file_path" == sys/* || "$file_path" == proc/* ]]; then
        continue
    fi

    dir_path=$(dirname "$file_path")
    base_name=$(basename "$file_path")

    # Create local directory structure
    mkdir -p "backup/$dir_path"

    # Copy file to local directory
    cd "backup/$dir_path"
    if [[ ! -f "$base_name" ]]; then
        # wait 1 second to avoid overwhelming the device
        # with too many files at once
        sleep 1
        adb pull "/$file_path" || echo "Failed to pull $file_path"
    fi
    
    cd - > /dev/null

done < files_shuffled.txt