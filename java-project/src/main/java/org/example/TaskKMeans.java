package org.example;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.execution.streaming.state.StreamingAggregationStateManagerImplV1;
import scala.Tuple2;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.apache.spark.sql.functions.col;

public class TaskKMeans implements Serializable {

    public static class DataPoint implements Serializable {
        private final double[] features;

        public DataPoint(double[] features) {
            this.features = features;
        }

        public double[] getFeatures() {
            return features;
        }

        @Override
        public String toString() {
            return "DataPoint{" + Arrays.toString(features) + '}';
        }

        // For convergence check, we need to compare centroids
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DataPoint dataPoint = (DataPoint) o;
            return Arrays.equals(features, dataPoint.features);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(features);
        }
    }

    /**
     * Calculates the Euclidean distance between two feature vectors (double[]).
     * @param v1 First vector.
     * @param v2 Second vector.
     * @return The Euclidean distance.
     */
    public static double euclideanDistance(double[] v1, double[] v2) {
        // TODO:
        double sum = 0.0;
        for (int i = 0; i < v1.length; i++) {
            double difference = v1[i] - v2[i];
            sum += difference * difference;
        }
        return Math.sqrt(sum);
    }

    /**
     * Finds the index of the closest centroid for a given data point.
     * @param point The data point.
     * @param centroids A list of current centroids.
     * @return The index of the closest centroid.
     */
    public static int findClosestCentroid(DataPoint point, List<DataPoint> centroids) {
        // TODO:
        double minDistance = Double.MAX_VALUE;
        int closestCentroidId = -1;

        double[] f = point.getFeatures();
        for (int i = 0; i < centroids.size(); i++) {
            double dist = euclideanDistance(f, centroids.get(i).getFeatures());
            if (dist < minDistance) {
                minDistance = dist;
                closestCentroidId = i;
            }
        }
        return closestCentroidId;
    }

    /**
     * Calculates the new centroid (mean) for a cluster of data points.
     * @param pointsInCluster An Iterable of data points belonging to one cluster.
     * @return A new DataPoint representing the mean of the cluster.
     */
    public static DataPoint calculateNewCentroid(Iterable<DataPoint> pointsInCluster) {
        // TODO:
        double[] sum = null;
        int count = 0;
        for (DataPoint p : pointsInCluster) {
            double[] f = p.getFeatures();
            if (sum == null) sum = new double[f.length];
            for (int i = 0; i < f.length; i++) sum[i] += f[i];
            count++;
        }
        if (count == 0) return new DataPoint(sum == null ? new double[]{0.0} : sum); // defensive
        for (int i = 0; i < sum.length; i++) sum[i] /= count;
        return new DataPoint(sum);
        // return new DataPoint(null /* newCentroidFeatures*/);
    }


    public static void run(boolean local) {
        //Initialize Spark Session
        SparkConf sparkConf = null;
        String sparkApplicationName = "ParallelKMeans";
        String datasetFileName = "dataset-showering.csv";
        String sparkMasterUrl = "spark://spark-master:7077";

        if(local){
            sparkConf = new SparkConf().setAppName(sparkApplicationName).setMaster("local[*]");
        }else {
            sparkConf = new SparkConf().setAppName(sparkApplicationName).setMaster(sparkMasterUrl);
        }

        // Initialize SparkSession using the configuration
        SparkSession sparkSession = SparkSession.builder().config(sparkConf).getOrCreate();
        JavaSparkContext jsc = new JavaSparkContext(sparkSession.sparkContext());

        // Load Data from CSV into a Dataset<Row>
        // Use SparkSession.read() to load CSV into a Dataset<Row>
        Dataset<Row> rawData = DatasetHelper.getDataset(sparkSession, datasetFileName, local);
        rawData = rawData.drop("timestamp").drop("unix_timestamp"); // records mit volumer unter 50 dl herausfiltern

        //Filter out potentially noisy data
        rawData = rawData.filter("volume >= 50");

        System.out.println("Schema of loaded CSV:");
        rawData.printSchema();
        System.out.println("First 5 rows of raw data:");
        rawData.show(5);

        // Split Data into Training and Test Sets using Dataset.randomSplit()
        double[] weights = {0.999, 0.001}; // 80% for training, 20% for testing
        long seed = 123L; // For reproducibility
        Dataset<Row>[] splits = rawData.randomSplit(weights, seed);
        Dataset<Row> trainingDataset = splits[0];
        Dataset<Row> testDataset = splits[1];
        System.out.println("Training data points (Dataset): " + trainingDataset.count());
        System.out.println("Test data points (Dataset): " + testDataset.count());

        // Convert Dataset<Row> to JavaRDD<DataPoint> for our custom K-Means algorithm
        // This step extracts the features (assuming all columns are features of type Double)
        JavaRDD<DataPoint> trainingDataRDD = trainingDataset.javaRDD().map(row -> {
            double[] features = new double[row.length()];
            for (int i = 0; i < row.length(); i++) {
                // Assuming all columns are numeric (doubles) and represent features
                features[i] = row.getDouble(i);
            }
            return new DataPoint(features);
        }).cache(); // Cache the RDD as it will be used multiple times

        JavaRDD<DataPoint> testDataRDD = testDataset.javaRDD().map(row -> {
            double[] features = new double[row.length()];
            for (int i = 0; i < row.length(); i++) {
                features[i] = row.getDouble(i);
            }
            return new DataPoint(features);
        }).cache(); // Cache the RDD as it will be used once for final assignment

        // K-Means Parameters
        final int k = 4; // Number of clusters
        final int maxIterations = 100;
        final double convergenceThreshold = 1e-4; // How much centroids can change before stopping

        // Initialize Centroids (randomly pick k data points from the TRAINING dataset)
        List<DataPoint> currentCentroids = trainingDataRDD.takeSample(false, k, new Random().nextLong());

        System.out.println("\nInitial Centroids:");
        currentCentroids.forEach(c -> System.out.println(Arrays.toString(c.getFeatures())));

        //=============================== Your code here ============================================

        // K-Means Iteration Loop (Training on trainingDataRDD)
        for (int iter = 0; iter < maxIterations; iter++) {
            System.out.println("\nIteration " + (iter + 1));

            // Broadcast current centroids to all worker nodes
            Broadcast<List<DataPoint>> centroidsBc = jsc.broadcast(currentCentroids);

            // E-step: Assign each training data point to its closest centroid
            JavaPairRDD<Integer, DataPoint> assignments = trainingDataRDD.mapToPair(p -> {
                int cid = findClosestCentroid(p, centroidsBc.value());
                return new Tuple2<>(cid, p);
            });

            // M-step: Calculate new centroids based on the mean of assigned points
            JavaPairRDD<Integer, DataPoint> newCentroidsById =
                    assignments.groupByKey().mapValues(TaskKMeans::calculateNewCentroid);

            // Collect new centroids to the driver and sort them by ID
            List<Tuple2<Integer, DataPoint>> collected =
                    new ArrayList<>(newCentroidsById.collect());   // jetzt mutable
            collected.sort(java.util.Comparator.comparingInt(Tuple2::_1));


            // Check for convergence
            boolean converged = true;
            List<DataPoint> nextCentroids = new ArrayList<>(currentCentroids);
            for (Tuple2<Integer, DataPoint> t : collected) {
                int id = t._1;
                DataPoint oldC = currentCentroids.get(id);
                DataPoint newC = t._2;

                double delta = euclideanDistance(oldC.getFeatures(), newC.getFeatures());
                if (delta > convergenceThreshold) converged = false;

                nextCentroids.set(id, newC);
            }

            // Update centroids for next iteration
            currentCentroids = nextCentroids;
            centroidsBc.destroy();

            System.out.println("Current Centroids:");
            currentCentroids.forEach(c -> System.out.println(Arrays.toString(c.getFeatures())));

            if (converged) {
                System.out.println("\nK-Means converged after " + (iter + 1) + " iterations.");
                break;
            }
        }

        // Testing Assign test data points to clusters
        System.out.println("\n--- Test Data Cluster Assignments ---");
        Broadcast<List<DataPoint>> finalCentroidsBroadcast = jsc.broadcast(currentCentroids);
        testDataRDD.mapToPair(point -> {
            int finalClusterId = findClosestCentroid(point, finalCentroidsBroadcast.value());
            return new Tuple2<>(point, finalClusterId);
        }).collect().forEach(result -> {
            System.out.println("Test Point: " + Arrays.toString(result._1().getFeatures()) +
                    ", Assigned Cluster: " + result._2());
        });


        // Stop Spark Session
        sparkSession.stop();
        jsc.close();
    }
}