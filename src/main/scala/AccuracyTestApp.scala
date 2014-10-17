/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.spark.SparkContext._
import org.apache.spark.mllib.clustering.HierarchicalClustering
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.uncommons.maths.random.XORShiftRNG


object AccuracyTestApp {

  def main(args: Array[String]) {
    val master = args(0)
    val maxCores = args(1)
    val numClusters = args(2).toInt
    val dimension = args(3).toInt
    val numPartitions = args(4).toInt

    val appName = s"${this.getClass().getSimpleName},maxCores,${maxCores},dim:${dimension},"
    val conf = new SparkConf()
        .setAppName(appName)
        .setMaster(master)
        .set("spark.cores.max", maxCores)
    val sc = new SparkContext(conf)

    val labeledData = generateData(sc, numPartitions, numClusters, dimension)
    labeledData.cache
    val model = HierarchicalClustering.train(labeledData.map(_._1), numClusters)
    val result = model.predict(labeledData.map(_._1)).map(_.swap)
    val joinedData = labeledData.zipWithIndex().map(_.swap).join(result.zipWithIndex().map(_.swap))

    val clusters = model.getClusters()
    joinedData.sparkContext.broadcast(clusters)
    val (diffTotal, n) = joinedData.map { case ((idx, ((vector1, seedVector), (vector2, closestIdx)))) =>
      val closestCluster = clusters(closestIdx)
      val d = (seedVector.toArray zip closestCluster.center.toArray)
          .map { case (elm1, elm2) => Math.pow(elm1 - elm2, 2.0)}
      (d, 1)
    }.reduce { case ((array1, n1), (array2, n2)) =>
      val array = (array1 zip array2).map { case (elm1, elm2) => elm1 + elm2}
      val n = n1 + n2
      (array, n)
    }

    // show the result
    println(s"==== Experiment Result ====")
    println(s"Total Rows: ${labeledData.count()}")
    println(s"Given # Clusters: ${numClusters}")
    println(s"Dimension: ${dimension}")
    println(s"Train Time: ${model.trainTime} [msec]")
    println(s"Predict Time: ${model.predictTime} [msec]")
    println(s"Total Diff Vector: ${diffTotal.map(_ / n).mkString(", ")}")
    println(s"== Centers: ${model.getClusters().size}")
    model.getClusters().map(_.center)
        .foreach(center => println(s"  ${center.toArray.mkString(",")}"))
    println(s"== Result Vectors and Their Rows: ")
    result.map { case (vector, closestIdx) => (closestIdx, 1)}.reduceByKey(_ + _)
        .map(_.swap).sortByKey().collect().foreach { case (count, closestIdx) =>
      val closestCluster = clusters(closestIdx)
      val vector = closestCluster.center
      println(s"  Count: ${count}, Variance: ${closestCluster.getVariance().get}, Seed Vector: ${vector.toArray.mkString(",")}")
    }
    println(s"== Seed Vectors and Their Rows: ")
    labeledData.map { case (vector, seedVector) => (seedVector, 1)}.reduceByKey(_ + _)
        .map(_.swap).sortByKey().collect().foreach { case (count, vector) =>
      println(s"  Count: ${count}, Seed Vector: ${vector.toArray.mkString(",")}")
    }
  }

  def generateData(sc: SparkContext,
    numPartitions: Int,
    exponent: Int,
    dim: Int): RDD[(Vector, Vector)] = {

    def generateSeedSeq(n: Int): Seq[Int] = {
      val seed = 10 * n
      val times = 1000
      n match {
        case 1 => (1 to n * times).map(i => seed).toSeq
        case _ => (1 to n * times).map(i => seed).toSeq ++ generateSeedSeq(n - 1)
      }
    }

    val random = new XORShiftRNG()
    sc.broadcast(random)
    sc.parallelize(generateSeedSeq(exponent), numPartitions).map { i =>
      val seedArray = (1 to dim).map(j => j.toDouble + i.toDouble).toArray
      val seedVector = Vectors.dense(seedArray)
      val vector = Vectors.dense(seedArray.map(elm => elm + 0.01 * elm * random.nextGaussian()))
      (vector, seedVector)
    }
  }
}