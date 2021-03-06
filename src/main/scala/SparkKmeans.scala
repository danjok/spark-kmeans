package main

import spark._
import spark.SparkContext._
import main.feature.Point
import com.codahale.jerkson.Json._
import scala.io._

object SparkKmeans {    
    // system configuration parameters
    val conf = parse[Map[String, String]](Source.fromFile("./spark.conf").mkString.trim)

    for ( key <- List( "host", "inputFile" ) ) {
        if (!conf.contains(key)) {
            System.err.println("Missing configuration key '" ++ key ++ "' in ./spark.conf")
            sys.exit(1)
        }
    }

    val host = conf("host").toString
    
    val sc = new SparkContext(host, "SparkKmeans",System.getenv("SPARK_HOME"),
      List[String]("./target/job.jar") )
    //val sc = new SparkContext(host, "SparkKmeans")

    val inputFile = conf("inputFile").toString
   
    // algorithm  parameters
    val k = 3
    val convergeDist = 0.1

    // Input and Parser
    /// Parse the points from a file into an RDD
    val points = sc.textFile(inputFile).map(
      line => {
        val parts = line.split(" ").map(_.toDouble)
          Point(parts(0), parts(1))
      }).cache

   
    println("Read " + points.count() + " points.")
    // Initialization
    val centroids = Array(Point(1.5, 5.0), Point(2.5, 3.0), Point(1.5, 1.0))

    // Start the Spark run
    val resultCentroids = kmeans(points, centroids, convergeDist, sc)

    println(resultCentroids.map(centroid => "%3f\t%3f\n".format(centroid.x, centroid.y)).mkString)
  

  def kmeans(points: spark.RDD[Point], centroids: Seq[Point], epsilon: Double, sc: SparkContext): Seq[Point] = {
    
    def closestCentroid(centroids: Seq[Point], point: Point) = {
      centroids.reduceLeft(
        //search for min distance
        (a, b) => if ((point distance a) < (point distance b)) a else b)
    }

    // Assignnment Step
    //the first map computes for each point the closest centroid, then emits pair (point,1), we have so <cc,[(p,1)]>
    //then a distributed reduction computes partial sums
    //finally a map computes for each centroid the new centroid
    //the result is a Map(k,v) with k oldCentroid and v newCentroid
    val clusters =
      (points
        .map(point => closestCentroid(centroids, point) -> (point, 1))
        .reduceByKeyToDriver({
          case ((ptA, numA), (ptB, numB)) => (ptA + ptB, numA + numB)
        })
        .map({
          case (centroid, (ptSum, numPts)) => centroid -> ptSum / numPts
        }))

    println(clusters)
    // Update Step
    val newCentroids = centroids.map(oldCentroid => {
      clusters.get(oldCentroid) match {
        case Some(newCentroid) => newCentroid
        case None => oldCentroid
      }
    })

    //Stopping condition
    // Calculate the centroid movement
    val movement = (centroids zip newCentroids).map({
      case (oldCentroid, newCentroid) => oldCentroid distance newCentroid
    })


    println("Centroids changed by\n" +
      "\t   " + movement.map(d => "%3f".format(d)).mkString("(", ", ", ")") + "\n" +
      "\tto " + newCentroids.mkString("(", ", ", ")"))

    // Iterate if movement exceeds threshold
    if (movement.exists(_ > epsilon))
      kmeans(points, newCentroids, epsilon, sc)
    else
      return newCentroids
  }
}
