package org.radargun.stages.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.radargun.DistStageAck;
import org.radargun.Operation;
import org.radargun.StageResult;
import org.radargun.Version;
import org.radargun.config.Init;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.reporting.Report;
import org.radargun.state.WorkerState;
import org.radargun.stats.Statistics;
import org.radargun.traits.InjectTrait;
import org.radargun.traits.Transactional;
import org.radargun.utils.TimeConverter;
import org.radargun.utils.TimeService;
import org.radargun.utils.Utils;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Base for test spawning several threads and benchmark of operations executed in those.")
public abstract class TestStage extends BaseTestStage {
   public static final String NAMESPACE = "urn:radargun:stages:cache:" + Version.SCHEMA_VERSION;
   public static final String DEPRECATED_NAMESPACE = "urn:radargun:stages:legacy:" + Version.SCHEMA_VERSION;
   protected static final String STRESSORS_MANAGER = "StressorsManager";

   @Property(doc = "The number of threads executing on each node. You have to set either this or 'total-threads'. No default.")
   public int numThreadsPerNode = 0;

   @Property(doc = "Total number of threads across whole cluster. You have to set either this or 'num-threads-per-node'. No default.")
   public int totalThreads = 0;

   @Property(doc = "Specifies if the requests should be explicitly wrapped in transactions. " +
      "Options are NEVER, ALWAYS and IF_TRANSACTIONAL: transactions are used only if " +
      "the cache configuration is transactional and transactionSize > 0. Default is IF_TRANSACTIONAL.")
   public TransactionMode useTransactions = TransactionMode.IF_TRANSACTIONAL;

   @Property(doc = "Specifies whether the transactions should be committed (true) or rolled back (false). " +
      "Default is true")
   public boolean commitTransactions = true;

   @Property(doc = "Number of requests in one transaction. Default is 1.")
   public int transactionSize = 1;

   @Property(doc = "Local threads synchronize on starting each round of requests. Note that with requestPeriod > 0, " +
      "there is still the random ramp-up delay. Default is false.")
   public boolean synchronousRequests = false;

   @Property(doc = "Max duration of the test. Default is infinite.", converter = TimeConverter.class)
   public long timeout = 0;

   @Property(doc = "Delay to let all threads start executing operations. Default is 0.", converter = TimeConverter.class)
   public long rampUp = 0;

   @Property(converter = TimeConverter.class, doc = "Time between consecutive requests of one stressor thread. Default is 0.", deprecatedName = "delayBetweenRequests")
   protected long thinkTime = 0;

   @Property(doc = "Whether an error from transaction commit/rollback should be logged as error. Default is true.")
   public boolean logTransactionExceptions = true;

   @Property(converter = TimeConverter.class, doc = "Intended time between each request. Default is 0. Change it to greater than 0 in order to have a compensate for CO")
   protected long cycleTime = 0;

   @Property(doc = "Enable this property in order to show the difference between latency and service.")
   protected boolean reportLatencyAsServiceTime;

   @Property(doc = "When true the stage will run in background. No stats will be available. Default false")
   protected boolean runBackground;

   @InjectTrait
   protected Transactional transactional;

   private Completion completion;
   private OperationSelector operationSelector;

   protected volatile boolean started = false;
   protected volatile boolean finished = false;
   protected volatile boolean terminated = false;

   protected StressorsManager stressorsManager;

   public StressorsManager getStressorsManager() {
      return stressorsManager;
   }

   @Init
   public void init() {
      if (totalThreads <= 0 && numThreadsPerNode <= 0)
         throw new IllegalStateException("You have to set either total-threads or num-threads-per-node.");
      if (totalThreads > 0 && numThreadsPerNode > 0)
         throw new IllegalStateException("You have to set only one ot total-threads, num-threads-per-node");
      if (totalThreads < 0 || numThreadsPerNode < 0) throw new IllegalStateException("Number of threads can't be < 0");
      if (cycleTime > 0 && thinkTime > 0) throw new IllegalStateException("We cannot mix cycleTime and thinkTime");
      if (reportLatencyAsServiceTime && cycleTime == 0) throw new IllegalStateException("Report Latency as Service Time can be enabled when cycleTime > 0");
   }

   public DistStageAck executeOnWorker() {
      if (!isServiceRunning()) {
         log.info("Not running test on this worker as service is not running.");
         return successfulResponse();
      }
      prepare();
      try {
         long startNanos = TimeService.nanoTime();
         log.info("Starting test " + testName);
         stressorsManager = setUpAndStartStressors();
         if (runBackground) {
            workerState.put(STRESSORS_MANAGER, stressorsManager);
            return successfulResponse();
         } else {
            StopTestStage.waitForStressorsToFinish(stressorsManager, timeout);
            destroy();
            log.info("Finished test. Test duration is: " + Utils.getNanosDurationString(TimeService.nanoTime() - startNanos));
            return newStatisticsAck(stressorsManager.getStressors());
         }
      } catch (Exception e) {
         return errorResponse("Exception while initializing the test", e);
      }
   }

   /**
    * To be overridden in inheritors.
    */
   protected void prepare() {
   }

   /**
    * To be overridden in inheritors.
    */
   protected void destroy() {
   }

   public StageResult processAckOnMain(List<DistStageAck> acks) {
      return processAckOnMain(acks, testName);
   }

   protected StageResult processAckOnMain(List<DistStageAck> acks, String testNameOverride) {
      StageResult result = super.processAckOnMain(acks);
      if (result.isError()) return result;

      Report.Test test = getTest(amendTest, testNameOverride);
      testIteration = test == null ? 0 : test.getIterations().size();
      // we cannot use aggregated = createStatistics() since with PeriodicStatistics the merge would fail
      List<StatisticsAck> statisticsAcks = instancesOf(acks, StatisticsAck.class);
      Statistics aggregated = statisticsAcks.stream().flatMap(ack -> ack.statistics.stream()).reduce(null, Statistics.MERGE);
      for (StatisticsAck ack : statisticsAcks) {
         if (ack.statistics != null) {
            if (test != null) {
               int testIteration = getTestIteration();
               String iterationValue = resolveIterationValue();
               if (iterationValue != null) {
                  test.setIterationValue(testIteration, iterationValue);
               }
               if (test.getGroupOperationsMap() == null) {
                  test.setGroupOperationsMap(ack.getGroupOperationsMap());
               }
               test.addStatistics(testIteration, ack.getWorkerIndex(), ack.statistics);
            }
         } else {
            log.trace("No statistics received from worker: " + ack.getWorkerIndex());
         }
      }
      if (checkRepeatCondition(aggregated)) {
         return StageResult.SUCCESS;
      } else {
         return StageResult.BREAK;
      }
   }

   protected StressorsManager setUpAndStartStressors() {
      long startTime = TimeService.currentTimeMillis();
      completion = createCompletion();
      CountDownLatch finishCountDown = new CountDownLatch(1);
      completion.setCompletionHandler(new Runnable() {
         @Override
         public void run() {
            //Stop collecting statistics for duration-based tests
            if (duration > 0) {
               finished = true;
            }
            finishCountDown.countDown();
         }
      });
      operationSelector = wrapOperationSelector(createOperationSelector());

      List<Stressor> stressors = startStressors();
      started = true;

      if (rampUp > 0) {
         try {
            Thread.sleep(rampUp);
         } catch (InterruptedException e) {
            throw new IllegalStateException("Interrupted during ramp-up.", e);
         }
      }
      return new StressorsManager(stressors, startTime, finishCountDown);
   }

   protected Completion createCompletion() {
      if (numOperations > 0) {
         long countPerNode = numOperations / getExecutingWorkers().size();
         long modCountPerNode = numOperations % getExecutingWorkers().size();
         if (getExecutingWorkerIndex() + 1 <= modCountPerNode) {
            countPerNode++;
         }
         return new CountStressorCompletion(countPerNode);
      } else {
         return new TimeStressorCompletion(duration);
      }
   }

   protected OperationSelector createOperationSelector() {
      return OperationSelector.DUMMY;
   }

   protected OperationSelector wrapOperationSelector(OperationSelector operationSelector) {
      if (synchronousRequests) {
         operationSelector = new SynchronousOperationSelector(operationSelector);
      }
      return operationSelector;
   }

   protected List<Stressor> startStressors() {
      int myFirstThread = getFirstThreadOn(workerState.getWorkerIndex());
      int myNumThreads = getNumThreadsOn(workerState.getWorkerIndex());
      CountDownLatch threadCountDown = new CountDownLatch(myNumThreads);

      List<Stressor> stressors = new ArrayList<>();
      for (int threadIndex = stressors.size(); threadIndex < myNumThreads; threadIndex++) {
         Stressor stressor = new Stressor(this, getLogic(), myFirstThread + threadIndex, threadIndex, threadCountDown);
         stressors.add(stressor);
         stressor.start();
      }
      try {
         threadCountDown.await();
      } catch (InterruptedException e) {
         //FIXME implement me
      }
      log.info("Started " + stressors.size() + " stressor threads.");
      return stressors;
   }

   protected DistStageAck newStatisticsAck(List<Stressor> stressors) {
      List<Statistics> results = gatherResults(stressors, new StatisticsResultRetriever());
      return new StatisticsAck(workerState, results, statisticsPrototype.getGroupOperationsMap());
   }

   protected <T> List<T> gatherResults(List<Stressor> stressors, ResultRetriever<T> retriever) {
      if (mergeThreadStats) {
         return stressors.stream()
            .map(retriever::getResult)
            .reduce(retriever::merge)
            .map(Collections::singletonList).orElse(Collections.emptyList());
      } else {
         return stressors.stream()
            .map(retriever::getResult)
            .filter(r -> r != null)
            .collect(Collectors.toList());
      }
   }

   public int getTotalThreads() {
      if (totalThreads > 0) {
         return totalThreads;
      } else if (numThreadsPerNode > 0) {
         return getExecutingWorkers().size() * numThreadsPerNode;
      } else throw new IllegalStateException();
   }

   public int getFirstThreadOn(int worker) {
      List<Integer> executingWorkers = getExecutingWorkers();
      int execId = executingWorkers.indexOf(worker);
      if (numThreadsPerNode > 0) {
         return execId * numThreadsPerNode;
      } else if (totalThreads > 0) {
         return execId * totalThreads / executingWorkers.size();
      } else {
         throw new IllegalStateException();
      }
   }

   public int getNumThreadsOn(int worker) {
      List<Integer> executingWorkers = getExecutingWorkers();
      if (numThreadsPerNode > 0) {
         return executingWorkers.contains(workerState.getWorkerIndex()) ? numThreadsPerNode : 0;
      } else if (totalThreads > 0) {
         int execId = executingWorkers.indexOf(worker);
         return (execId + 1) * totalThreads / executingWorkers.size() - execId * totalThreads / executingWorkers.size();
      } else {
         throw new IllegalStateException();
      }
   }

   protected Statistics createStatistics() {
      return statisticsPrototype.copy();
   }

   public boolean isStarted() {
      return started;
   }

   public boolean isFinished() {
      return finished;
   }

   public boolean isTerminated() {
      return terminated;
   }

   public void setTerminated() {
      terminated = true;
      stressorsManager.getFinishCountDown().countDown();
   }

   public Completion getCompletion() {
      return completion;
   }

   public OperationSelector getOperationSelector() {
      return operationSelector;
   }

   public boolean useTransactions(String resourceName) {
      return useTransactions.use(transactional, resourceName, transactionSize);
   }

   public abstract OperationLogic getLogic();

   public boolean isSingleTxType() {
      return transactionSize == 1;
   }

   protected interface ResultRetriever<T> {
      T getResult(Stressor stressor);

      T merge(T stats1, T stats2);
   }

   protected static class StatisticsResultRetriever implements ResultRetriever<Statistics> {
      public StatisticsResultRetriever() {}

      @Override
      public Statistics getResult(Stressor stressor) {
         return stressor.getStats();
      }

      @Override
      public Statistics merge(Statistics stats1, Statistics stats2) {
         return Statistics.MERGE.apply(stats1, stats2);
      }
   }

   protected static class StatisticsAck extends DistStageAck {
      public final List<Statistics> statistics;
      private final Map<String, Set<Operation>> groupOperationsMap;

      public StatisticsAck(WorkerState workerState, List<Statistics> statistics, Map<String, Set<Operation>> groupOperationsMap) {
         super(workerState);
         this.statistics = statistics;
         this.groupOperationsMap = groupOperationsMap;
      }
      public Map<String, Set<Operation>> getGroupOperationsMap() {
         return groupOperationsMap;
      }
   }
}
