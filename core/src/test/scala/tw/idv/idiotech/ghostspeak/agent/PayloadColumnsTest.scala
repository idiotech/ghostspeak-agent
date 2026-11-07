package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class PayloadColumnsTest extends AnyFlatSpec with Matchers {

  "a MEDIUMBLOB or LONGBLOB payload column" must "be big enough" in {
    PayloadColumns.tooSmall("mediumblob") mustBe None
    PayloadColumns.tooSmall("longblob") mustBe None
    PayloadColumns.tooSmall("LONGBLOB") mustBe None
  }

  "a smaller payload column" must "be reported with what it holds" in {
    PayloadColumns.tooSmall("blob") mustBe Some("blob, which holds at most 64 KB")
    PayloadColumns.tooSmall("tinyblob") mustBe Some("tinyblob, which holds at most 255 bytes")
    PayloadColumns.tooSmall("varbinary") mustBe Some("varbinary, not a MEDIUMBLOB or LONGBLOB")
  }
}
