package archery

import scala.collection.mutable.ArrayBuffer
import scala.math.{ceil, min, max}
import scala.util.Random.nextFloat

import org.scalacheck.Arbitrary._
import org.scalatest._
import prop._

import org.scalacheck._
import Gen._
import Arbitrary.arbitrary

class RTreeCheck extends PropSpec with Matchers with GeneratorDrivenPropertyChecks {

  import GeomUtil._

  implicit val arbentry = Arbitrary(for {
    g <- arbitrary[Geom]; n <- arbitrary[Int]
  } yield Entry(g, n))

  def build(es: List[Entry[Int]]): RTree[Int] =
    es.foldLeft(RTree.empty[Int])(_ insert _)

  property("rtree.insert works") {
    forAll { (es: List[Entry[Int]]) =>
      val rt = build(es)
      rt.root.entries.toSet shouldBe es.toSet
    }
  }

  property("rtree.contains works") {
    forAll { (es: List[Entry[Int]], e: Entry[Int]) =>
      val rt = build(es)
      es.forall(rt.contains) shouldBe true

      rt.contains(e) shouldBe es.contains(e)
    }
  }

  property("rtree.remove works") {
    forAll { (es: List[Entry[Int]]) =>
      val rt = build(es)

      val rt2 = es.foldLeft(rt)(_ remove _)
      rt2.root.entries.isEmpty shouldBe true
    }
  }

  def shuffle[A](buf: ArrayBuffer[A]): Unit = {
    for (i <- 1 until buf.length) {
      val j = scala.util.Random.nextInt(i)
      val t = buf(i)
      buf(i) = buf(j)
      buf(j) = t
    }
  }

  property("rtree.remove out-of-order") {
    forAll { (es: List[Entry[Int]]) =>
      val buf = ArrayBuffer(es: _*)
      shuffle(buf)
      var rt = build(es)
      while (buf.nonEmpty) {
        buf.toSet shouldBe rt.entries.toSet
        val x = buf.remove(0)
        rt = rt.remove(x)
      }
      buf.toSet shouldBe rt.entries.toSet
    }
  }

  val mile = 1600F

  def bound(g: Geom, n: Int): Box = {
    val d = 10F * mile
    Box(g.x - d, g.y - d, g.x2 + d, g.y2 + d)
  }

  property("rtree.search/count ignores nan/inf") {
    forAll { (es: List[Entry[Int]], p: Point) =>
      val rt = build(es)
      val nil = Seq.empty[Entry[Int]]

      rt.search(Box(Float.PositiveInfinity, 3F, 9F, 9F)) shouldBe nil
      rt.search(Box(2F, Float.NaN, 9F, 9F)) shouldBe nil
      rt.search(Box(2F, 3F, Float.NegativeInfinity, 9F)) shouldBe nil
      rt.search(Box(2F, 3F, 9F, Float.NaN)) shouldBe nil

      rt.count(Box(Float.PositiveInfinity, 3F, 9F, 9F)) shouldBe 0
      rt.count(Box(2F, Float.NaN, 9F, 9F)) shouldBe 0
      rt.count(Box(2F, 3F, Float.NegativeInfinity, 9F)) shouldBe 0
      rt.count(Box(2F, 3F, 9F, Float.NaN)) shouldBe 0
    }
  }

  property("rtree.search by bbox works") {
    forAll { (es: List[Entry[Int]], p: Point) =>
      val rt = build(es)

      val box1 = bound(p, 10)
      rt.search(box1).toSet shouldBe es.filter(e => box1.contains(e.geom)).toSet

      es.foreach { e =>
        val box2 = bound(e.geom, 10)
        rt.search(box2).toSet shouldBe es.filter(e => box2.contains(e.geom)).toSet
      }
    }
  }

  property("rtree.search by radius works") {
    forAll { (es: List[Entry[Int]], p: Point) =>
      val rt = build(es)
      val radius = 10

      def control(p: Point, radius: Int): Set[Entry[Int]] = {
        es.filter(e => e.geom.maxDistance(p) <= radius).toSet
      }

      rt.search(p, radius).toSet shouldBe control(p, radius)
      es.foreach { e =>
        val p2 = e.geom.lowerLeft
        rt.search(p2, radius).toSet shouldBe control(p2, radius)
      }
    }
  }

  property("rtree.searchIntersection works") {
    forAll { (es: List[Entry[Int]], p: Point) =>
      val rt = build(es)

      val box1 = bound(p, 10)
      rt.searchIntersection(box1).toSet shouldBe es.filter(e => box1.intersects(e.geom)).toSet

      es.foreach { e =>
        val box2 = bound(e.geom, 10)
        rt.searchIntersection(box2).toSet shouldBe es.filter(e => box2.intersects(e.geom)).toSet
      }
    }
  }

  property("rtree.nearest works") {
    forAll { (es: List[Entry[Int]], p: Point) =>
      val rt = build(es)
      if (es.isEmpty) {
        rt.nearest(p) shouldBe None
      } else {
        val e = es.min(Ordering.by((e: Entry[Int]) => e.geom.distance(p)))
        val d = e.geom.distance(p)
        // it's possible that several points are tied for closest
        // in these cases, the distances still must be equal.
        rt.nearest(p).map(_.geom.distance(p)) shouldBe Some(d)
      }
    }
  }

  property("rtree.nearestK works") {
    forAll { (es: List[Entry[Int]], p: Point, k0: Int) =>
      val k = (k0 % 1000).abs
      val rt = build(es)

      val as = es.map(_.geom.distance(p)).sorted.take(k).toVector
      val bs = rt.nearestK(p, k).map(_.geom.distance(p))
      as shouldBe bs
    }
  }

  sealed trait Action {
    def test(rt: RTree[Int]): RTree[Int]
    def control(es: List[Entry[Int]]): List[Entry[Int]]
  }

  object Action {
    def run(rt: RTree[Int], es: List[Entry[Int]])(as: List[Action]): Unit =
      as match {
        case a :: as =>
          val rt2 = a.test(rt)
          val es2 = a.control(es)
          rt2.entries.toSet shouldBe es2.toSet
          run(rt2, es2)(as)
        case Nil =>
          ()
      }
  }

  case class Insert(e: Entry[Int]) extends Action {
    def test(rt: RTree[Int]): RTree[Int] =
      rt.insert(e)
    def control(es: List[Entry[Int]]): List[Entry[Int]] =
      e :: es
  }

  case class Remove(e: Entry[Int]) extends Action {
    def test(rt: RTree[Int]): RTree[Int] =
      if (rt.contains(e)) rt.remove(e) else rt
    def control(es: List[Entry[Int]]): List[Entry[Int]] =
      es match {
        case Nil => Nil
        case `e` :: t => t
        case h :: t => h :: control(t)
      }
  }

  implicit val arbaction = Arbitrary(for {
    e <- arbitrary[Entry[Int]]
    b <- arbitrary[Boolean]
  } yield {
    val a: Action = if (b) Insert(e) else Remove(e)
    a
  })

  property("ad-hoc rtree") {
    forAll { (es: List[Entry[Int]], as: List[Action]) =>
      Action.run(build(es), es)(as)
    }
  }
}
