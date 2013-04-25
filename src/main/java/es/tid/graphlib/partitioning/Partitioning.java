package es.tid.graphlib.partitioning;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.giraph.Algorithm;
import org.apache.giraph.aggregators.IntSumAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;

/**
 * Demonstrates the Pregel-based implementation of an adaptive partitioning
 * algorithm for Large-Scale Dynamic Graphs.
 */

@Algorithm(name = "Adaptive Partitioning for Large-Scale Dynamic Graphs",
  description = "This is a scalable graph partitioning algorithm that: "
    + "(a) Produces k-way balanced partitions. "
    + "(b) Minimizes the number of cut edges until convergence. "
    + "(c) Adapts to dynamic graph changes with minimum cost. ")

public class Partitioning extends Vertex<IntWritable,
  IntWritable, IntWritable, IntMessageWrapper> {
  public static final String PROBABILITY = "partitioning.probability";
  public static final float PROBABILITY_DEFAULT = 0.5f;
  public static final String NUM_PARTITIONS = "partitioning.num.partition";
  public static final int NUM_PARTITIONS_DEFAULT = 1;
  public static final String DELTA_CACHING = "partitioning.delta.caching";
  public static final boolean DELTA_CACHING_DEFAULT = false;
  public static final String AGGREGATOR_CAPACITY_PREFIX = "AGG_CAP_";
  public static final String AGGREGATOR_DEMAND_PREFIX = "AGG_DEM_";
  public static final IntWritable PLUS_ONE = new IntWritable(1);
  public static final IntWritable MINUS_ONE = new IntWritable(-1);
  /** Aggregator to get values from the workers to the master */
  //public static final String CAPACITY_AGGREGATOR= "capacity.aggregator";
  /** Iterations */
  public static final int ITERATIONS = 30;
  /** Total Capacity for each partition */
  public static int CAPACITY = 0;
  /** Vertex Residual Capacity */
  public int resCapacity = 0;
  /** Counter of number of migrations */
  public int countMigrations = 0;

  public void compute(Iterable<IntMessageWrapper> messages) {
    /* Parameter for number of partitions */
    int numPartitions =getContext().getConfiguration().getInt(NUM_PARTITIONS,
        NUM_PARTITIONS_DEFAULT);
    /* Parameter for probability number */
    double probability = getContext().getConfiguration().getFloat(PROBABILITY,
        PROBABILITY_DEFAULT);
    /* Flag for checking if delta caching is enabled */
    boolean deltaFlag = getContext().getConfiguration().getBoolean(
      DELTA_CACHING, DELTA_CACHING_DEFAULT);
    /* HashMap<PartitionID, weight_2migrage_2PartitionID> */
    HashMap<IntWritable, DoubleWritable> weightedPartition =
      new HashMap<IntWritable, DoubleWritable>();
    /* HashMap<PartitionID, num_neighbours_in_PartitionID> */
    HashMap<IntWritable, IntWritable> countNeigh =
      new HashMap<IntWritable, IntWritable>();
    int migrate2partition = 0;
    int load = 0;
    int availability = 0;
    int demand = 0;
    Random randomGenerator = new Random();
    double finalProbability = 0d;
    System.out.println("***** SS:" + getSuperstep() + ", vertexID: " + getId());

    /* Superstep: 0 -- Initialize Vertex Value & Compute CAPACITY */
    if (getSuperstep() == 0) {
      initValue(numPartitions);
      CAPACITY = (int) (getTotalNumVertices() / numPartitions +
        getTotalNumVertices() * 0.2);
      System.out.println("partitionID: " + getValue() +
        " [residual=CAPACITY-numVerticesCurrentPartition]: " + resCapacity +
        "= " + CAPACITY + " - "/* + numVerticesCurrentPartition*/);

      /* Send to capacity aggregator a PLUS_ONE signal */
      aggregate(AGGREGATOR_CAPACITY_PREFIX + getValue().get(), PLUS_ONE);
    }

    if (getSuperstep() > 0) {
      if (getSuperstep() % 2 == 1) {
        /* Odd Supersteps: Show interest to migrate to a partition */
        //int numNeighPartitions = 0;
        /* For each message */
        for (IntMessageWrapper message : messages) {
          System.out.println("  [RECEIVE] from " + message.getSourceId()
            + ", " + message.getMessage());
          if (deltaFlag) {
            // TODO: may have not received msgs from all neighbors
          }
          /* Count number of neighbors in neighbor's partition */
          if (!countNeigh.containsKey(message.getMessage())) {
            countNeigh.put(message.getMessage(), new IntWritable(1));
            System.out.println("countNeigh<ParID, count>:" +
              countNeigh.get(message.getMessage()));
            weightedPartition.put(message.getMessage(),
              new DoubleWritable(0d));
            //numNeighPartitions++;
          } else {
            countNeigh.put(message.getMessage(),
              new IntWritable(countNeigh.get(message.getMessage()).get()+1));
            System.out.println("countNeigh<ParID, count>:" +
              countNeigh.get(message.getMessage()));
          }
          /* Introduce random factor for migrating or not */
          randomGenerator = new Random();
          migrate2partition = 0;
          /* Allow migration only with certain probability */
          if (randomGenerator.nextDouble() < probability) {
            /* Calculate the weight of migration to each partition 
             * that has neighbors */
            for (Map.Entry<IntWritable, DoubleWritable> partition:
                weightedPartition.entrySet()){
              load = CAPACITY - ((IntWritable)
                getAggregatedValue(AGGREGATOR_CAPACITY_PREFIX)).get();
              weightedPartition.put(partition.getKey(),
                new DoubleWritable(1d - (double)load/CAPACITY *
                ((countNeigh.get(partition.getKey())).get()/getNumEdges())));
            }
          }
          migrate2partition = maxWeightedPartition(weightedPartition);
          if (migrate2partition != getValue().get()) {
            aggregate(AGGREGATOR_DEMAND_PREFIX + migrate2partition, MINUS_ONE);
          }
        } // EoF messages()
      } else {
        /* Even Supersteps: Migrate to partition */
        availability = CAPACITY - load;
        demand = ((IntWritable)
          getAggregatedValue(AGGREGATOR_DEMAND_PREFIX)).get();
        finalProbability = (double) availability / demand;
        if (randomGenerator.nextDouble() < finalProbability) {
          migrate(migrate2partition);
        }
      }
    } // EoF getSuperstep()>0
    /* Send to neighbors: vertex residual capacity */
    if (getSuperstep() < ITERATIONS) {
      sendMessage();
    }
  } // EoF compute()

  /**
   * Send message to neighbors
   *
   * @param resCapacity     Residual Capacity
   */
  public void sendMessage() {
    /* Send Message to all neighbors*/
    for (Edge<IntWritable, IntWritable> edge : getEdges()) {
      /* Create a message and wrap together the source id and the message */
      IntMessageWrapper message = new IntMessageWrapper();
      message.setSourceId(getId());
      message.setMessage(getValue());
      sendMessage(edge.getTargetVertexId(), message);
      System.out.println("  [SEND] to " + edge.getTargetVertexId() +
        " residual_capacity: " + message.getMessage());
    } // End of for each edge
  }

  /**
   * Move a vertex from current partition to a new partition
   *
   * @param migrate2partition     PartitionID to be migrated to
   */
  public void migrate(int migrate2partition){
    // Remove vertex from current partition
    aggregate(AGGREGATOR_CAPACITY_PREFIX + getValue().get(), MINUS_ONE);
    // Add vertex to new partition
    aggregate(AGGREGATOR_CAPACITY_PREFIX + migrate2partition, MINUS_ONE);
    countMigrations+=1;
  }

  /**
   * Return partition with maximum number of neighbors
   *
   * @param HashMap<PartitionID, weight> weightedPart
   *  Weight for each partition
   *
   * @return partitionID    Partition ID with the maximum weight
   */
  public int maxWeightedPartition(HashMap<IntWritable,
      DoubleWritable> weightedPartition) {
    Map.Entry<IntWritable, DoubleWritable> maxEntry = null;
    for (Map.Entry<IntWritable, DoubleWritable> entry :
        weightedPartition.entrySet()) {
      if (maxEntry == null ||
        entry.getValue().compareTo(maxEntry.getValue()) > 0) {
          maxEntry = entry;
      } else
        if (entry.getValue().compareTo(maxEntry.getValue()) == 0){
          if (entry.getKey() == getValue()) {
            maxEntry = entry;
          }
        }
    }
    return maxEntry.getKey().get();
  }

  /**
   * Initialize Vertex Value with the equation:
   * VertexValue = VertexID mod num_of_partitions
   *
   * @param numPartitions       Number of Partitions
   */
  public void initValue(int numPartitions) {
    setValue(new IntWritable(getId().get() % numPartitions));
  }

  /**
   * MasterCompute used with {@link SimpleMasterComputeVertex}.
   */
  public static class MasterCompute extends DefaultMasterCompute {
    @Override
    public void initialize() throws InstantiationException,
      IllegalAccessException {
      // Create aggregators - one for each partition
      for (int i=0; i< getContext().getConfiguration().getInt(NUM_PARTITIONS,
          NUM_PARTITIONS_DEFAULT); i++) {
        registerPersistentAggregator(AGGREGATOR_CAPACITY_PREFIX + i,
            IntSumAggregator.class);
        registerAggregator(AGGREGATOR_DEMAND_PREFIX + i,
            IntSumAggregator.class);
      }
    } // EoF initialize()
  } // EoF class MasterCompute{}
}
