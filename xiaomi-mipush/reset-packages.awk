function exact(line, name) {
  return index(line, "\"" name "\"") > 0
}
function at_end(line, pat) {
  if (pat == "</package>" && index(line, "</packages>") > 0) {
    return 0
  }
  return index(line, pat) > 0
}
function target(line) {
  return exact(line, "com.xiaomi.xmsf")
}
BEGIN { skip = 0; endpat = "" }
{
  if (skip) {
    if (at_end($0, endpat)) {
      skip = 0
    }
    next
  }
  if (!target($0)) {
    print
    next
  }
  if (index($0, "<package ") > 0) {
    endpat = "</package>"
  } else if (index($0, "<package-state ") > 0) {
    endpat = "</package-state>"
  } else if (index($0, "<user-states ") > 0) {
    endpat = "</user-states>"
  } else if (index($0, "<updated-package ") > 0) {
    endpat = "</updated-package>"
  } else if (index($0, "<item ") > 0) {
    endpat = "/>"
  } else {
    print
    next
  }
  if (index($0, "/>") > 0 || at_end($0, endpat)) {
    next
  }
  skip = 1
}
