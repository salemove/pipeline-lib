package com.salemove

class Collections {
  static def addWithoutDuplicates(precedenceList, otherList, Closure selector) {
    def otherListFiltered = otherList.findAll { otherItem ->
      def isInPrecedenceList = precedenceList.any { precedenceItem ->
        selector(precedenceItem) == selector(otherItem)
      }
      !isInPrecedenceList
    }
    precedenceList + otherListFiltered
  }

  static def joinWithAnd(list) {
    if (list.size() <= 1) {
      // The separator value will be ignored, but this ensures the behavior of
      // this function is the same as for #join in case of 0 and 1 elements.
      list.join('')
    } else if (list.size() == 2) {
      list.join(' and ')
    } else {
      "${list.init().join(', ')}, and ${list.last()}"
    }
  }
}
