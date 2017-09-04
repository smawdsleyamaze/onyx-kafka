(ns onyx.plugin.kafka
  (:require [franzy.admin.cluster :as k-cluster]
            [franzy.admin.zookeeper.client :as k-admin]
            [franzy.serialization.serializers :refer [byte-array-serializer]]
            [franzy.serialization.deserializers :refer [byte-array-deserializer]]
            [franzy.clients.producer.client :as producer]
            [franzy.clients.producer.protocols :refer [send-async! send-sync!]]
            [franzy.clients.producer.types :refer [make-producer-record]]
            [franzy.clients.consumer.client :as consumer]
            [franzy.clients.producer.callbacks :refer [send-callback]]
            [franzy.common.metadata.protocols :as metadata]
            [franzy.clients.consumer.protocols :refer [seek-to-offset!] :as cp]
            [onyx.compression.nippy :refer [zookeeper-compress zookeeper-decompress]]
            [onyx.plugin.partition-assignment :refer [partitions-for-slot]]
            [taoensso.timbre :as log :refer [fatal info]]
            [onyx.static.default-vals :refer [arg-or-default]]
            [onyx.plugin.protocols :as p]
            [onyx.static.util :refer [kw->fn]]
            [onyx.tasks.kafka]
            [schema.core :as s]
            [onyx.api])
  (:import [java.util.concurrent.atomic AtomicLong]
           [org.apache.kafka.clients.consumer ConsumerRecords ConsumerRecord]
           [org.apache.kafka.clients.consumer KafkaConsumer ConsumerRebalanceListener Consumer]
           [org.apache.kafka.common TopicPartition]
           [org.apache.kafka.clients.producer Callback]
           [franzy.clients.consumer.client FranzConsumer]
           [franzy.clients.producer.client FranzProducer]
           [franzy.clients.producer.types ProducerRecord]))

(def defaults
  {:kafka/receive-buffer-bytes 65536
   :kafka/commit-interval 2000
   :kafka/wrap-with-metadata? false
   :kafka/unable-to-find-broker-backoff-ms 8000})

(defn seek-offset! [log-prefix consumer kpartitions task-map topic checkpoint]
  (let [policy (:kafka/offset-reset task-map)
        start-offsets (:kafka/start-offsets task-map)]
    (doseq [kpartition kpartitions]
      (cond (get checkpoint kpartition)
            (let [offset (get checkpoint kpartition)]
              (info log-prefix "Seeking to checkpointed offset at:" (inc offset))
              (seek-to-offset! consumer {:topic topic :partition kpartition} (inc offset)))

            start-offsets
            (let [offset (get start-offsets kpartition)]
              (when-not offset
                (throw (ex-info "Offset missing for existing partition when using :kafka/start-offsets"
                                {:missing-partition kpartition
                                 :kafka/start-offsets start-offsets})))
              (seek-to-offset! consumer {:topic topic :partition kpartition} offset))

            (= policy :earliest)
            (do
              (info log-prefix "Seeking to earliest offset on topic" {:topic topic :partition kpartition})
              (cp/seek-to-beginning-offset! consumer [{:topic topic :partition kpartition}]))

            (= policy :latest)
            (do
              (info log-prefix "Seeking to latest offset on topic" {:topic topic :partition kpartition})
              (cp/seek-to-end-offset! consumer [{:topic topic :partition kpartition}]))

            :else
            (throw (ex-info "Tried to seek to unknown policy" {:recoverable? false
                                                               :policy policy}))))))

;; kafka operations
(defn id->broker [zk-addr]
  (with-open [zk-utils (k-admin/make-zk-utils {:servers zk-addr} false)]
    (reduce
     (fn [result {:keys [id endpoints]}]
       (assoc
        result
        id
        (str (:host (first endpoints)) ":" (:port (first endpoints)))))
     {}
     (k-cluster/all-brokers zk-utils))))

(defn find-brokers [task-map]
  (let [zk-addr (:kafka/zookeeper task-map)
        results (vals (id->broker zk-addr))]
    (if (seq results)
      results
      (do
       (info "Could not locate any Kafka brokers to connect to. Backing off.")
       (Thread/sleep (or (:kafka/unable-to-find-broker-backoff-ms task-map) 
                         (:kafka/unable-to-find-broker-backoff-ms defaults)))
       (throw (ex-info "Could not locate any Kafka brokers to connect to."
                       {:recoverable? true
                        :zk-addr zk-addr}))))))

(defn start-kafka-consumer
  [event lifecycle]
  {})

(defn check-num-peers-equals-partitions 
  [{:keys [onyx/min-peers onyx/max-peers onyx/n-peers kafka/partition] :as task-map} n-partitions]
  (let [fixed-partition? (and partition (or (= 1 n-peers)
                                            (= 1 max-peers)))
        fixed-npeers? (or (= min-peers max-peers) (= 1 max-peers)
                          (and n-peers (and (not min-peers) (not max-peers))))
        n-peers (or max-peers n-peers)
        n-peers-less-eq-n-partitions (<= n-peers n-partitions)] 
    (when-not (or fixed-partition? fixed-npeers? n-peers-less-eq-n-partitions)
      (let [e (ex-info ":onyx/min-peers must equal :onyx/max-peers, or :onyx/n-peers must be set, and :onyx/min-peers and :onyx/max-peers must not be set. Number of peers should also be less than or equal to the number of partitions."
                       {:n-partitions n-partitions 
                        :n-peers n-peers
                        :min-peers min-peers
                        :max-peers max-peers
                        :recoverable? false
                        :task-map task-map})] 
        (log/error e)
        (throw e)))))

(defn assign-partitions-to-slot! [consumer* task-map topic n-partitions slot]
  (if-let [part (:partition task-map)]
    (let [p (Integer/parseInt part)]
      (cp/assign-partitions! consumer* [{:topic topic :partition p}])
      [p])
    (let [n-slots (or (:onyx/n-peers task-map) (:onyx/max-peers task-map))
          [lower upper] (partitions-for-slot n-partitions n-slots slot)
          parts-range (range lower (inc upper))
          parts (map (fn [p] {:topic topic :partition p}) parts-range)]
      (cp/assign-partitions! consumer* parts)
      parts-range)))

(deftype KafkaReadMessages 
  [log-prefix task-map topic ^:unsynchronized-mutable kpartitions batch-timeout
   deserializer-fn segment-fn read-offset ^:unsynchronized-mutable consumer 
   ^:unsynchronized-mutable iter ^:unsynchronized-mutable partition->offset ^:unsynchronized-mutable drained]
  p/Plugin
  (start [this event]
    (let [{:keys [kafka/group-id kafka/consumer-opts]} task-map
          brokers (find-brokers task-map)
          _ (s/validate onyx.tasks.kafka/KafkaInputTaskMap task-map)
          consumer-config (merge {:bootstrap.servers brokers
                                  :group.id (or group-id "onyx")
                                  :enable.auto.commit false
                                  :receive.buffer.bytes (or (:kafka/receive-buffer-bytes task-map)
                                                            (:kafka/receive-buffer-bytes defaults))
                                  :auto.offset.reset (:kafka/offset-reset task-map)}
                                 consumer-opts)
          _ (info log-prefix "Starting kafka/read-messages task with consumer opts:" consumer-config)
          key-deserializer (byte-array-deserializer)
          value-deserializer (byte-array-deserializer)
          consumer* (consumer/make-consumer consumer-config key-deserializer value-deserializer)
          partitions (mapv :partition (metadata/partitions-for consumer* topic))
          n-partitions (count partitions)]
      (check-num-peers-equals-partitions task-map n-partitions)
      (let [kpartitions* (assign-partitions-to-slot! consumer* task-map topic n-partitions (:onyx.core/slot-id event))]
        (set! consumer consumer*)
        (set! kpartitions kpartitions*)
        this)))

  (stop [this event] 
    (when consumer 
      (.close ^FranzConsumer consumer)
      (set! consumer nil))
    this)

  p/Checkpointed
  (checkpoint [this]
    partition->offset)

  (recover! [this replica-version checkpoint]
    (set! drained false)
    (set! iter nil)
    (set! partition->offset checkpoint)
    (seek-offset! log-prefix consumer kpartitions task-map topic checkpoint)
    this)

  (checkpointed! [this epoch])

  p/BarrierSynchronization
  (synced? [this epoch]
    true)

  (completed? [this]
    drained)

  p/Input
  (poll! [this _]
    (if (and iter (.hasNext ^java.util.Iterator iter))
      (let [rec ^ConsumerRecord (.next ^java.util.Iterator iter)
            deserialized (some-> rec segment-fn)]
        (cond (= :done deserialized)
              (do (set! drained true)
                  nil)
              deserialized
              (let [new-offset (.offset rec)
                    part (.partition rec)]
                (.set ^AtomicLong read-offset new-offset)
                (set! partition->offset (assoc partition->offset part new-offset))
                deserialized)))
      (do (set! iter 
                (.iterator ^ConsumerRecords 
                           (.poll ^Consumer (.consumer ^FranzConsumer consumer) 
                                  batch-timeout)))
          nil))))

(defn read-messages [{:keys [onyx.core/task-map onyx.core/log-prefix onyx.core/monitoring] :as event}]
  (let [{:keys [kafka/topic kafka/deserializer-fn]} task-map
        batch-timeout (arg-or-default :onyx/batch-timeout task-map)
        wrap-message? (or (:kafka/wrap-with-metadata? task-map) (:kafka/wrap-with-metadata? defaults))
        deserializer-fn (kw->fn (:kafka/deserializer-fn task-map))
        segment-fn (if wrap-message?
                     (fn [^ConsumerRecord cr]
                       {:topic (.topic cr)
                        :partition (.partition cr)
                        :key (.key cr)
                        :message (deserializer-fn (.value cr))
                        :offset (.offset cr)})
                     (fn [^ConsumerRecord cr]
                       (deserializer-fn (.value cr))))
        read-offset (:read-offset monitoring)]
    (->KafkaReadMessages log-prefix task-map topic nil batch-timeout
                         deserializer-fn segment-fn read-offset nil nil nil false)))

(defn close-read-messages
  [event lifecycle]
  {})

(defn inject-write-messages
  [event lifecycle]
  {})

(defn close-write-resources
  [event lifecycle]
  {})

(defn- message->producer-record
  [serializer-fn topic kpartition m]
  (let [message (:message m)
        k (some-> m :key serializer-fn)
        message-topic (get m :topic topic)
        message-partition (some-> m (get :partition kpartition) int)]
    (cond (not (contains? m :message))
          (throw (ex-info "Payload is missing required. Need message key :message"
                          {:recoverable? false
                           :payload m}))

          (nil? message-topic)
          (throw (ex-info
                  (str "Unable to write message payload to Kafka! "
                       "Both :kafka/topic, and :topic in message payload "
                       "are missing!")
                  {:recoverable? false
                   :payload m}))

          :else
          (ProducerRecord. message-topic message-partition k (serializer-fn message)))))

(defn clear-write-futures! [write-futures]
  (vswap! write-futures 
          (fn [fs] (doall (remove realized? fs)))))

(defrecord KafkaWriteMessages [task-map config topic kpartition producer serializer-fn write-futures exception write-callback]
  p/Plugin
  (start [this event] 
    this)

  (stop [this event] 
    (.close ^FranzProducer producer)
    this)

  p/BarrierSynchronization
  (synced? [this epoch]
    (empty? (clear-write-futures! write-futures)))

  (completed? [this]
    (empty? (clear-write-futures! write-futures)))

  p/Checkpointed
  (recover! [this _ _] 
    this)
  (checkpoint [this])
  (checkpointed! [this epoch])

  p/Output
  (prepare-batch [this event replica _]
    true)
  (write-batch [this {:keys [onyx.core/results]} replica _]
    (when @exception (throw @exception))
    (vswap! write-futures 
            (fn [fs]
              (into (doall (remove realized? fs))
                    (comp (mapcat :leaves)
                          (map (fn [msg]
                                 (send-async! producer 
                                              (message->producer-record serializer-fn topic kpartition msg)
                                              {:send-callback write-callback}))))
                    (:tree results))))
    true))

(def write-defaults {:kafka/request-size 307200})

(deftype ExceptionCallback [e]
  Callback
  (onCompletion [_ v exception]
    (reset! e exception)))

(defn write-messages [{:keys [onyx.core/task-map onyx.core/log-prefix] :as event}]
  (let [_ (s/validate onyx.tasks.kafka/KafkaOutputTaskMap task-map)
        request-size (or (get task-map :kafka/request-size) (get write-defaults :kafka/request-size))
        producer-opts (:kafka/producer-opts task-map)
        config (merge {:bootstrap.servers (vals (id->broker (:kafka/zookeeper task-map)))
                       :max.request.size request-size}
                      producer-opts)
        _ (info log-prefix "Starting kafka/write-messages task with producer opts:" config)
        topic (:kafka/topic task-map)
        kpartition (:kafka/partition task-map)
        key-serializer (byte-array-serializer)
        value-serializer (byte-array-serializer)
        producer (producer/make-producer config key-serializer value-serializer)
        serializer-fn (kw->fn (:kafka/serializer-fn task-map))
        exception (atom nil)
        write-callback (->ExceptionCallback exception)
        write-futures (volatile! (list))]
    (->KafkaWriteMessages task-map config topic kpartition producer serializer-fn 
                          write-futures exception write-callback)))

(defn read-handle-exception [event lifecycle lf-kw exception]
  (if (false? (:recoverable? (ex-data exception)))
    :kill
    :restart))

(def read-messages-calls
  {:lifecycle/before-task-start start-kafka-consumer
   :lifecycle/handle-exception read-handle-exception
   :lifecycle/after-task-stop close-read-messages})

(defn write-handle-exception [event lifecycle lf-kw exception]
  (if (false? (:recoverable? (ex-data exception)))
    :kill
    :restart))

(def write-messages-calls
  {:lifecycle/before-task-start inject-write-messages
   :lifecycle/handle-exception write-handle-exception
   :lifecycle/after-task-stop close-write-resources})
