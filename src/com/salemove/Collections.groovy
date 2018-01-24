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
}
