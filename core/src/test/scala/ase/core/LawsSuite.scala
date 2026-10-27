package ase.core

import munit.DisciplineSuite

class LawsSuite extends DisciplineSuite:
  checkAll("core", CoreLaws.term)
  checkAll("core", CoreLaws.envelope)
  checkAll("core", CoreLaws.dryRun)
