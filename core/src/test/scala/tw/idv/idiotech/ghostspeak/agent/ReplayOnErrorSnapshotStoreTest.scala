package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ReplayOnErrorSnapshotStoreTest extends AnyWordSpec with Matchers {

  "the record of skipped snapshots" should {
    "hand a skipped persistence id back once" in {
      ReplayOnErrorSnapshotStore.snapshotSkipped("scn-graphscript-a")
      ReplayOnErrorSnapshotStore.wasSnapshotSkipped("scn-graphscript-a") shouldBe true
      ReplayOnErrorSnapshotStore.wasSnapshotSkipped("scn-graphscript-a") shouldBe false
    }

    "not know an id whose snapshot was never skipped" in {
      ReplayOnErrorSnapshotStore.wasSnapshotSkipped("scn-graphscript-never") shouldBe false
    }

    "keep ids apart" in {
      ReplayOnErrorSnapshotStore.snapshotSkipped("scn-graphscript-b")
      ReplayOnErrorSnapshotStore.wasSnapshotSkipped("scn-graphscript-c") shouldBe false
      ReplayOnErrorSnapshotStore.wasSnapshotSkipped("scn-graphscript-b") shouldBe true
    }
  }
}
