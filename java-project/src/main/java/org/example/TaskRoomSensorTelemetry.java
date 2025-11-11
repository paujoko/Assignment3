package org.example;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.apache.spark.sql.functions.*;

public class TaskRoomSensorTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRoomSensorTelemetry.class);
    public static void run(boolean local){
        SparkSession sparkSession = null;

        try {
            LOGGER.info("Starting CO2 Pattern Analysis.");

            String sparkApplicationName = "RoomSensorTelemetry";
            String datasetFileName = "dataset-room-sensors.csv";
            SparkConf sparkConf = null;
            String sparkMasterUrl = "spark://spark-master:7077";

            if(local){
                sparkConf = new SparkConf().setAppName(sparkApplicationName).setMaster("local[*]");
            }else {
                sparkConf = new SparkConf().setAppName(sparkApplicationName).setMaster(sparkMasterUrl);
            }

            // Initialize SparkSession
            sparkSession = SparkSession.builder().config(sparkConf).getOrCreate();
            LOGGER.info("SparkSession initialized. SparkContext log level set to WARN.");

            // Retrieve data and form the dataframe
            Dataset<Row> df = DatasetHelper.getDataset(sparkSession, datasetFileName, local);

            //=================================== Your code now =========================================

            //-------------------------------------------------------------------------------------------
            // Step A: Calculate Average CO2 per hour grouped by month
            // Order by month and then hour to ensure correct sequence for window function within each month
            LOGGER.info("\n--- Calculate Average CO2 per hour grouped by month ---");
            //Here I used de pre extracted variables from the DatasetHelper (such as month)

            Dataset<Row> hourlyAvgCo2 = df
                    .filter(col("month").gt(0))
                    .groupBy(col("month"), col("hour"))//groups over months and hours
                    .agg(avg(col("co2")).alias("avg_co2")) //calculates mean
                    .orderBy(col("month"), col("hour"));

            //You can use show to debug if things are going well. But remove show before deploying.
            System.out.println("\n--- Hourly Average CO2 with Month (first 10 rows) ---");
            hourlyAvgCo2.show(10);


            //-------------------------------------------------------------------------------------------

            // Step B: Calculate the difference between consecutive hourly averages within each month
            // The window function is now partitioned by 'month'. This means 'lag' will
            // only look at previous rows within the same month partition.

            WindowSpec windowSpec = Window.partitionBy("month").orderBy("hour");
            // at first we calculate every difference between every 2 hours in a month
            Dataset<Row> co2Differences = hourlyAvgCo2
                    .withColumn("prev_avg_co2", lag(col("avg_co2"), 1).over(windowSpec))
                    .withColumn("diff_co2", col("avg_co2").minus(col("prev_avg_co2")));

            //Helpful debug output
            System.out.println("\n--- Hourly CO2 Changes per Month (first 10 rows) ---");
            co2Differences.show(10);



            //-------------------------------------------------------------------------------------------
            // Step C: Find the maximum increase and maximum decrease for *each month*
            // We group by 'month' and then aggregate to find the max/min changes.
            // The 'when' clause ensures we only consider positive changes for max increase
            // and negative changes for max decrease. If a month has no increases/decreases,
            // the corresponding result will be null.

            Dataset<Row> monthWiseResults = co2Differences
                    .groupBy(col("month"))
                    .agg(max( when(col("diff_co2").gt(0), col("diff_co2"))
                                    .otherwise(lit(null)).cast("double") ).alias("max_increase"),
                            min( when(col("diff_co2").lt(0), col("diff_co2"))
                                    .otherwise(lit(null)).cast("double") ).alias("max_decrease"))
                    .orderBy(col("month"));

            System.out.println("\n--- Month-wise maximum CO2 increase and decrease results ---");
            monthWiseResults.show();


            //-------------------------------------------------------------------------------------------
            // Step D: find the correlation between month and CO2 (Hint: this is a one-liner :)
            double monthCorrelation = df.stat().corr("month", "co2");
            System.out.printf("Global Correlation between month of year and CO2: %.4f%n%n", monthCorrelation);

            // Similarly, between month and CO2--> Do you mean hour??
            double hourCorrelation = df.stat().corr("hour", "co2");
            System.out.printf("Global Correlation between hour of day and CO2: %.4f%n%n", hourCorrelation);

            // And, between weekday and CO2
            double weekdayCorrelation = df.stat().corr("weekday", "co2");
            System.out.printf("Global Correlation between day of week and CO2: %.4f%n%n", weekdayCorrelation);
            //-------------------------------------------------------------------------------------------

            //What you see? Which factor affects CO2 in room most?

            LOGGER.info("Analysis completed successfully.");

        } catch (Exception e) {
            LOGGER.error("An error occurred during Spark application execution: " + e.getMessage(), e);
        } finally {
            if (sparkSession != null) {
                sparkSession.stop();
                LOGGER.info("SparkSession stopped.");
            }
        }
    }
}
