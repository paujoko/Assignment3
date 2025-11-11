# Assignment 3 Report

## Team Members

Paul Kottmann, Lukas Kapferer, Amelia Gasser


## Responses to questions posed in the assignment

_Note:_ Include the Spark execution history for each task. Name the zip file as `assignment-3-task-<x>-history.zip`.

### Task 1: Word counting

1. If you were given an additional requirement of excluding certain words (for example, conjunctions), at which step you would do this and why? (0.1 pt)



2. In Lecture 1, the potential of optimizing the mapping step through combined mapping and reduce was discussed. How would you use this in this task? (in your answer you can either provide a description or a pseudo code). Optional: Implement this optimization and observe the effect on performance (i.e., time taken for completion). (0.1 pt)



3. In local execution mode (i.e. standalone mode), change the number of cores that is allocated by the master (.setMaster("local[<n>]") and measure the time it takes for the applicationto complete in each case. For each value of core allocation, run the experiment 5 times (to rule out large variances). Plot a graph showing the time taken for completion (with standard deviation) vs the number of cores allocated. Interpret and explain the results briefly in few sentences. (0.4 pt)


4. Examine the execution history. Explain your observations regarding the planning of jobs, stages, and tasks. (0.4 pt)


### Task 2

1. For each of the above computation, analyze the execution history and describe the key stages and tasks that were involved. In particular, identify where data shuffling occurred and explain why. (0.5pt)
   
   From the Spark History Server we observed that each computation produced multiple stages: source (5/5 tasks, ca. 597 MiB from input) and shuffle stages (4/4 tasks, ca. 70–76 MiB shuffle).
   - Step A (groupBy and  orderBy):
   Major shuffles in stages 8–13 (ca. 70–76 MiB) caused by grouping by (month,hour) and global sorting by (month,hour) to compute average CO₂ levels.
   - Step B (Window lag):
   Shuffles in stages 18–23 due to repartitioning by month and sorting by hour required by the window specification.
   - Step C (groupBy month):
   Shuffles in stages 43–48 (ca. 70–76 MiB) when aggregating max/min CO2 changes per month, as Spark redistributes data so that all rows of each month are on the same node.
   - Step D (corr computations):
   Each corr (month/hour/weekday, co2) triggered one global shuffle (stages 75–104) to combine partial aggregates from all partitions.


3. You had to manually partition the data. Why was this essential? Which feature of the dataset did you use to partition and why?(0.5pt)
   
Why was this essential: With partitioning we can tell spark what values (such as all values of one month) should be stored together in one partitioning on the same worker: we tell spark what should be in one Partition (like January is one, February is one, etc.). Thus, Spark can be make group based and window calculations more efficient, when similar values are on the same worker to make calculations, because it reduces expensive data movement across the nodes. This is a preparation for the next (expensive) stage, the shuffle (so one partition, like January, can go to one worker, for the calculations on January. ). With proper partitioning we prepare the data, so the expensive shuffeling only happens once and not every time a operation on one group is needed.

Used feature: we have partitioned the data by months and hours (partitionBy("month") and partitionBy(„hour“)) because our calculations (such as group averages and time-based windows) need all values from the same month and hour to be analyzed together.


3. Optional: Notice that in the already provided pre-processing (in the class DatasetHelper), the long form of timeseries data, i.e., with a column _field that contained values like temperature etc., has been converted to wide form, i.e. individual column for each measurement kind through and operation called pivoting. Analyze the execution log and describe why this happens to be an expensive transformation.

### Task 3

1. Explain how the K-Means program you have implemented, specifically the centroid estimation and recalculation, is parallelized by Spark (0.5pt)


## Declarations (if any)
