BEGIN {
    number = "^-?[0-9]+([.][0-9]+)?$"
}

/^\| Design Timing Summary[[:space:]]*$/ {
    in_summary = 1
    next
}

in_summary && $1 ~ number && $2 ~ number && $5 ~ number && $6 ~ number {
    print $1, $2, $5, $6
    found = 1
    exit
}

END {
    if (!found) {
        exit 2
    }
}
