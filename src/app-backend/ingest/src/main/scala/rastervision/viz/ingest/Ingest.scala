package rastervision.viz.ingest

import geotrellis.vector._
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

import scala.reflect._
import scala.reflect.runtime.universe._

object Ingest {
  def main(args: Array[String]): Unit = {
    val (layerName, tileSize) =
      if(args.length == 1) {
        val ts = args(0)
        (s"accumulated-random-nlcd-2011-30m-epsg5070-ts${ts}", ts.toInt)
      } else {
        // 512, default, ingested with no ts name.
        ("accumulated-random-nlcd-2011-30m-epsg5070", 512)
      }

    println(s"TILE SIZE: $tileSize")

    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName(s"Marching Perimeter Test Ingest")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    implicit val sc = new SparkContext(conf)
    try {
      val layerReader = S3LayerReader("datahub-catalogs-us-east-1", "")
      val layerDeleter = S3LayerDeleter("datahub-test-catalogs-us-east-1", "marching-perimeter-test")
      val layerWriter = S3LayerWriter("datahub-test-catalogs-us-east-1", "marching-perimeter-test")

      for(layerId <- layerReader.attributeStore.layerIds) {
        println(layerId)
      }
      val layerId = LayerId("nlcd-2011-30m-epsg5070-0.10.0", 0)
      val outLayerId = LayerId(layerName, 0)

      if(layerDeleter.attributeStore.layerExists(outLayerId)) {
        layerDeleter.delete(outLayerId)
      }

      val layer = {
        val l: TileLayerRDD[SpatialKey] =
          if(tileSize == 512) {
            layerReader
              .read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](layerId, numPartitions = 10000)
          } else {
            println(s"REPROJECTING TO CHANGE TILE SIZE TO $tileSize")
            val md = layerReader.attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](layerId)
            val tl = md.layout.tileLayout
            val r = 512 / tileSize
            val ntl = TileLayout(tl.layoutCols * r, tl.layoutRows * r, tileSize, tileSize)

            val layoutDefinition =
              LayoutDefinition(
                md.layout.extent,
                ntl
              )

            val newMd = md.copy(layout=layoutDefinition)
            println(newMd.toJson.prettyPrint)

            val tiled =
              layerReader
                .read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](layerId, numPartitions = 10000)
                .asRasters
                .map { case (key, raster) => (ProjectedExtent(raster.extent, md.crs), raster.tile) }
                .tileToLayout(newMd)

            ContextRDD(tiled, newMd)
          }

        l.withContext { rdd =>
          rdd.mapValues { case tile =>
            val bands = Array.ofDim[Tile](64)
            for(b <- 0 until 64) {
              bands(b) = tile.convert(IntConstantNoDataCellType)
            }
            MultibandTile(bands)
          }
        }
      }

      layerWriter.write(outLayerId, layer, ZCurveKeyIndexMethod)
    } finally {
      sc.stop
    }
  }
}
