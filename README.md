## onyx-kafka

Onyx plugin providing read and write facilities for Kafka. This plugin automatically discovers broker locations from ZooKeeper and updates the consumers when there is a broker failover.

#### Installation

In your project file:

```clojure
[org.onyxplatform/onyx-kafka "0.7.2.0"]
```

In your peer boot-up namespace:

```clojure
(:require [onyx.plugin.kafka])
```

#### Functions

##### read-messages

Reads segments from one Kafka topic's partition.

Catalog entry:

```clojure
{:onyx/name :read-messages
 :onyx/plugin :onyx.plugin.kafka/read-messages
 :onyx/type :input
 :onyx/medium :kafka
 :kafka/topic "my topic"
 :kafka/partition "0"
 :kafka/group-id "onyx-consumer"
 :kafka/fetch-size 307200
 :kafka/chan-capacity 1000
 :kafka/zookeeper "127.0.0.1:2181"
 :kafka/offset-reset :smallest
 :kafka/force-reset? true
 :kafka/empty-read-back-off 500
 :kafka/commit-interval 500
 :kafka/deserializer-fn :my.ns/deserializer-fn
 :onyx/max-peers 1
 :onyx/batch-size 100
 :onyx/doc "Reads messages from a Kafka topic"}
```

Lifecycle entry:

```clojure
{:lifecycle/task :read-messages
 :lifecycle/calls :onyx.plugin.kafka/read-messages-calls}
```

##### write-messages

Writes segments to a Kafka topic.

Catalog entry:

```clojure
{:onyx/name :write-messages
 :onyx/plugin :onyx.plugin.kafka/write-messages
 :onyx/type :output
 :onyx/medium :kafka
 :kafka/topic "topic"
 :kafka/zookeeper "127.0.0.1:2181"
 :kafka/serializer-fn :my.ns/serializer-fn
 :kafka/partitioner-class "kafka.producer.DefaultPartitioner"
 :onyx/batch-size batch-size
 :onyx/doc "Writes messages to a Kafka topic"}
```

Lifecycle entry:

```clojure
{:lifecycle/task :write-messages
 :lifecycle/calls :onyx.plugin.kafka/write-messages-calls}
```

#### Attributes

|key                         | type      | default | description
|----------------------------|-----------|---------|------------
|`:kafka/topic`              | `string`  |         | The topic name to connect to
|`:kafka/partition`          | `string`  |         | The partition to read from
|`:kafka/group-id`           | `string`  |         | The consumer identity to store in ZooKeeper
|`:kafka/zookeeper`          | `string`  |         | The ZooKeeper connection string
|`:kafka/offset-reset`       | `keyword` |         | Offset bound to seek to when not found - `:smallest` or `:largest`
|`:kafka/force-reset?`       | `boolean` |         | Force to read from the beginning or end of the log, as specified by `:kafka/offset-reset`. If false, reads from the last acknowledged messsage if it exists
|`:kafka/chan-capacity`      | `integer` |`1000`   | The buffer size of the Kafka reading channel
|`:kafka/fetch-size`         | `integer` |`307200` | The size in bytes to request from ZooKeeper per fetch request
|`:kafka/empty-read-back-off`| `integer` |`500`    | The amount of time to back off between reads when nothing was fetched from a consumer
|`:kafka/commit-interval`    | `integer` |`2000`   | The interval in milliseconds to commit the latest acknowledged offset to ZooKeeper
|`:kafka/serializer-fn`      | `keyword` |         | A keyword that represents a fully qualified namespaced function to serialize a message. Takes one argument - the segment
|`:kafka/deserializer-fn`    | `keyword` |         | A keyword that represents a fully qualified namespaced function to deserialize a message. Takes one argument - a byte array

#### Test Utilities

A take-segments utility function is provided for use when testing the results
of jobs with kafka output tasks. take-segments reads from a topic until a :done
is reached, and then returns the results. Note, if a `:done` is never written to a
topic, this will hang forever as there is no timeout.

```clojure
(ns your-ns.a-test
  (:require [onyx.kafka.utils :as kpu]))

;; insert code to run a job here

;; retrieve the segments on the topic
(def results
  (kpu/take-segments (:zookeeper/addr peer-config) "yourtopic" your-decompress-fn))

(last results)
; :done

```

#### Embedded Kafka Server

An embedded Kafka server is included for use in test cases.

This can be used like so:

```clojure
(ns your-ns.a-test
  (:require [onyx.kafka.embedded-server :as ke]
            [com.stuartsierra.component :as component]))

(def kafka-server
  (component/start
    (ke/map->EmbeddedKafka {:hostname "127.0.0.1"
                            :port 9092
                            :broker-id 0
			    :num-partitions 1
			    ; optional log dir name - randomized dir will be created
			    ; if none is supplied
                            ; :log-dir "/tmp/embedded-kafka"
                            :zookeeper-addr "127.0.0.1:2188"})))


;; insert code to run a test here


;; stop the embedded server
(component/stop kafka-server)

```

#### Contributing

Pull requests into the master branch are welcomed.

#### License

Copyright © 2015 Michael Drogalis

Distributed under the Eclipse Public License, the same as Clojure.
