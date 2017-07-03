package com.azavea.server

import geotrellis.vector._
import geotrellis.vector.io._
import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.resample._
import geotrellis.raster.merge._
import geotrellis.raster.prototype._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.avro._
import geotrellis.spark.io.index._
import geotrellis.spark.io.s3._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.file._
import geotrellis.spark.io.kryo.KryoRegistrator
import geotrellis.spark.pyramid._
import geotrellis.spark.reproject._
import geotrellis.spark.tiling._
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.serializer.KryoSerializer

import spray.json._

import scala.collection.mutable
import scala.reflect._
import scala.reflect.runtime.universe._

import java.io._

object JarResource {
  def apply(name: String): String = {
    val stream: InputStream = getClass.getResourceAsStream(s"/$name")
    try { scala.io.Source.fromInputStream( stream ).getLines.mkString(" ") } finally { stream.close() }
  }
}

object Main {
  def time[T](msg: String)(f: => T): Long = {
    val start = System.currentTimeMillis
    val v = f
    val end = System.currentTimeMillis
    val dur = end - start
    println(s"[TIMING] $msg: ${java.text.NumberFormat.getIntegerInstance.format(dur)} ms")
    dur
  }

  def write(path: String, txt: String): Unit = {
    import java.nio.file.{Paths, Files}
    import java.nio.charset.StandardCharsets

    Files.write(Paths.get(path), txt.getBytes(StandardCharsets.UTF_8))
  }

  def marchingAlgo(layer: MultibandTileLayerCollection[SpatialKey], poly: MultiPolygon): Map[(Int, Int), Long] = {
    // This is NLCD (16) x Soil (4).
    // A bit off, since it doesn't account for soil-with-no-nlcd or vica versa (an extra 20 bands)
    val numberOfCombos = 64

    import geotrellis.raster.rasterize.Rasterizer.Options
    import geotrellis.raster.{GridBounds, RasterExtent, PixelIsArea}

    import spire.syntax.cfor._

    val mapTransform = layer.metadata.layout.mapTransform
      val multiLine = MultiLine(poly.polygons.flatMap { poly => poly.exterior +: poly.holes })

    val comboMap = Array.ofDim[(Int, Int)](64)
    var i = 0
    cfor(0)(_ < 16, _ + 1) { nlcd =>
      cfor(0)(_< 4, _ + 1) { soil =>
        comboMap(i) = ((nlcd, soil))
        i += 1
      }
    }

    layer
      .groupBy { case (key, tile) => SpatialKey(key.col, 0) }
      .map { case (_, tiles) =>
        val bandCount = tiles.head._2.bandCount
        val lastSeen = Array.ofDim[Double](bandCount).fill(Double.NaN)
        val accumulatedValues = Array.ofDim[Long](bandCount) // 0's

        tiles.toSeq
          .sortBy(_._1.row)
          .map { case (key, tile) =>
            val re = RasterExtent(mapTransform(key), tile.cols, tile.rows)
            val intersectingCells = mutable.Set[(Int, Int)]()
            multiLine.foreach(re, Options(includePartial=true, sampleType=PixelIsArea)) { (col, row) =>
              intersectingCells += ((col, row))
            }

            intersectingCells.toSeq.sorted.foreach { case (col, row) =>
              cfor(0)(_ < bandCount, _ + 1) { b =>
                val v = tile.band(b).get(col, row)
                if(isData(v)) {
                  val ls = lastSeen(b)
                  if(isData(ls)) {
                    accumulatedValues(b) += ((v - ls).toLong)
                  }
                  lastSeen(b) = v
                }
              }
            }
          }

        accumulatedValues
      }
      .reduce { (acc1, acc2) =>
        cfor(0)(_ < acc1.size, _ + 1) { b =>
          acc1(b) += acc2(b)
        }
        acc1
      }
      .zipWithIndex
      .map { case (v, i) => (comboMap(i), v) }
      .toMap
  }

  def regularAlgo(
    layer1: TileLayerCollection[SpatialKey],
    layer2: TileLayerCollection[SpatialKey],
    polygon: MultiPolygon
  ): Map[(Int, Int), Long] = {
    val mapTransform = layer1.metadata.layout.mapTransform

    val m1 = layer1.toMap
    val m2 = layer2.toMap

    val result = mutable.Map[(Int, Int), Long]()

    for(k <- m1.keys.toSet.intersect(m2.keys.toSet)) {
      val (nlcd, soil) = (m1(k), m2(k))
      val re = RasterExtent(mapTransform(k), nlcd.cols, nlcd.rows)

      polygon.foreach(re) { (col, row) =>
        val v1 = nlcd.get(col, row)
        val v2 = soil.get(col, row)
        if(isData(v1) && isData(v2)) {
          val k = (v1, v2)
          if(!result.contains(k)) { result(k) = 0 }
          result(k) += 1
        }
      }
    }

    result.toMap
  }

  def main(args: Array[String]): Unit = {
    val aLayers =
      Seq(
        (32, LayerId("accumulated-random-nlcd-2011-30m-epsg5070-ts32", 0)),
        (128, LayerId("accumulated-random-nlcd-2011-30m-epsg5070-ts128", 0))
//        (512, LayerId("accumulated-random-nlcd-2011-30m-epsg5070", 0)) // 512
      )

    val baseLayerReader =  S3CollectionLayerReader("datahub-catalogs-us-east-1", "")
    val accLayerReader = S3CollectionLayerReader("datahub-test-catalogs-us-east-1", "marching-perimeter-test")

    val nlcdLayerId = LayerId("nlcd-2011-30m-epsg5070-0.10.0", 0)
    val soilLayerId = LayerId("ssurgo-hydro-groups-30m-epsg5070-0.10.0", 0)

    val polys = Seq(
      ("Hand drawn around Wilmington", poly1),
      ("From example MapshedJob_NHD", fromExample),
      ("Pennsylvania", pa)
    )

    val rows = mutable.ListBuffer[(String, Double, String, Long)]()

    for((name, poly) <- polys) {
      val area = poly.area
      val ms = time(s"Baseline") {
        val layer1 =
          baseLayerReader
            .query[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](nlcdLayerId)
            .where(Intersects(poly)).result
        val layer2 =
          baseLayerReader
            .query[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](soilLayerId)
            .where(Intersects(poly)).result

        println(s"Baseline NLCD count: ${layer1.size}")
        println(s"Baseline Soil count: ${layer2.size}")
        regularAlgo(
          layer1,
          layer2,
          poly
        )
      }
      rows += ((name, area, "Baseline", ms))

      val multiLine = MultiLine(poly.polygons.flatMap { poly => poly.exterior +: poly.holes })

      for((tileSize, aLayer) <- aLayers) {
        val ms = time(s"Accumulater $aLayer") {
          val layer =
            accLayerReader
              .query[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](aLayer)
              .where(Intersects(multiLine)).result
          println(s"$aLayer count: ${layer.size}")
          marchingAlgo(
            layer,
            poly
          )
        }
        rows += ((name, area, s"Marching: $tileSize x $tileSize", ms))
      }
    }

    var s = """"Polygon Name","Polygon Area","Algo Name","Time (ms)"\n"""
    for(r <- rows) {
      s += s""""${r._1}","${r._2}","${r._3}","${r._4}"\n"""
    }

    write("timings.csv", s)
  }

  val poly1 = MultiPolygon("""
{
        "type": "Polygon",
        "coordinates": [
          [
            [
              -76.783447265625,
              38.44068226417387
            ],
            [
              -76.7010498046875,
              38.831149809348744
            ],
            [
              -77.069091796875,
              39.27053717095511
            ],
            [
              -76.915283203125,
              39.48284540453334
            ],
            [
              -76.6790771484375,
              39.53793974517628
            ],
            [
              -76.79443359375,
              39.6437675734185
            ],
            [
              -77.1624755859375,
              39.67337039176558
            ],
            [
              -77.398681640625,
              39.38526381099774
            ],
            [
              -77.6678466796875,
              39.50404070558415
            ],
            [
              -77.86560058593749,
              39.46164364205549
            ],
            [
              -77.904052734375,
              39.33429742980725
            ],
            [
              -77.95898437499999,
              39.18117526158749
            ],
            [
              -78.1842041015625,
              39.11727568585598
            ],
            [
              -78.1842041015625,
              39.33854604847979
            ],
            [
              -78.37646484375,
              39.58452390500424
            ],
            [
              -78.760986328125,
              39.41497702499074
            ],
            [
              -78.651123046875,
              39.10022600175347
            ],
            [
              -78.3544921875,
              38.81403111409755
            ],
            [
              -78.5302734375,
              38.58252615935333
            ],
            [
              -78.2501220703125,
              38.39764411353178
            ],
            [
              -77.926025390625,
              38.29424797320529
            ],
            [
              -77.794189453125,
              38.50948995925553
            ],
            [
              -78.0084228515625,
              38.67264490154078
            ],
            [
              -77.82714843749999,
              38.843986129756615
            ],
            [
              -77.5799560546875,
              38.46219172306828
            ],
            [
              -77.7117919921875,
              37.94419750075404
            ],
            [
              -77.28881835937499,
              37.93986540897977
            ],
            [
              -77.2613525390625,
              38.20797181420939
            ],
            [
              -77.069091796875,
              38.13455657705411
            ],
            [
              -77.200927734375,
              38.363195134453846
            ],
            [
              -77.398681640625,
              38.634036452919226
            ],
            [
              -77.091064453125,
              38.44068226417387
            ],
            [
              -77.025146484375,
              38.591113776147445
            ],
            [
              -76.9482421875,
              38.38472766885085
            ],
            [
              -76.871337890625,
              38.33734763569314
            ],
            [
              -76.80541992187499,
              38.3287297527893
            ],
            [
              -76.728515625,
              38.36750215395045
            ],
            [
              -76.7010498046875,
              38.42777351132902
            ],
            [
              -76.783447265625,
              38.44068226417387
            ]
          ]
        ]
      }
""".parseGeoJson[Polygon].reproject(LatLng, CRS.fromEpsgCode(5070)))

  val fromExample = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-75.220813751220703,39.932512033740309],[-75.150260925292969,39.929221201562726],[-75.120391845703125,39.924350479594167],[-75.124168395996094,39.894723763816785],[-75.192832946777344,39.89643587838578],[-75.229225158691406,39.903810652180944],[-75.224761962890625,39.928957928153828],[-75.220813751220703,39.932512033740309]]]]}".parseGeoJson[MultiPolygon].reproject(LatLng, CRS.fromEpsgCode(5070))

  val pa = MultiPolygon(JarResource("pa.json").parseGeoJson[Polygon].reproject(LatLng, CRS.fromEpsgCode(5070)))
}
