(ns qbits.alia
  (:require
   [qbits.knit :as knit]
   [qbits.alia.codec :as codec]
   [qbits.alia.codec.eaio-uuid]
   [qbits.alia.utils :as utils]
   [qbits.alia.cluster-options :as copt])
  (:import
   [com.datastax.driver.core
    BoundStatement
    Cluster
    Cluster$Builder
    ConsistencyLevel
    PreparedStatement
    Query
    ResultSet
    ResultSetFuture
    Session
    SimpleStatement]
   [com.google.common.util.concurrent
    Futures
    FutureCallback]
   [java.nio ByteBuffer]))

(def ^:dynamic *consistency* :one)
(def consistency-levels (utils/enum-values->map (ConsistencyLevel/values)))

(defmacro with-consistency
  "Binds qbits.alia/*consistency*"
  [consistency & body]
  `(binding [qbits.alia/*consistency* ~consistency]
     ~@body))

(defn set-consistency!
  "Sets the consistency globally"
  [consistency]
  (alter-var-root #'*consistency*
                  (constantly consistency)
                  (when (thread-bound? #'*consistency*)
                    (set! *consistency* consistency))))

(def ^:dynamic *session*)

(defmacro with-session
  "Binds qbits.alia/*session*"
  [session & body]
  `(binding [qbits.alia/*session* ~session]
     ~@body))

(defn set-session!
  "Sets the session globally"
  [session]
  (alter-var-root #'*session*
                  (constantly session)
                  (when (thread-bound? #'*session*)
                    (set! *session* session))))

(def ^:dynamic *executor* (knit/executor :cached))

(defmacro with-executor
  "Binds qbits.alia/*executor*"
  [executor & body]
  `(binding [qbits.alia/*executor* ~executor]
     ~@body))

(defn set-executor!
  "Sets the executor globally"
  [executor]
  (alter-var-root #'*executor*
                  (constantly executor)
                  (when (thread-bound? #'*executor*)
                    (set! *executor* executor))))

(defn cluster
  "Returns a new com.datastax.driver.core/Cluster instance"
  [hosts & {:as options}]
  (-> (Cluster/builder)
      (copt/set-cluster-options! (assoc options :contact-points hosts))
      .build))

(defn ^Session connect
  "Returns a new com.datastax.driver.core/Session instance. We need to
have this separate in order to allow users to connect to multiple
keyspaces from a single cluster instance"
  ([^Cluster cluster keyspace]
     (.connect cluster keyspace))
  ([^Cluster cluster]
     (.connect cluster)))

(defn shutdown
  "Shutdowns Session or Cluster instance, clearing the underlying
pools/connections"
  ([cluster-or-session]
     (.shutdown cluster-or-session))
  ([]
     (shutdown *session*)))

(defn prepare
  "Returns a com.datastax.driver.core.PreparedStatement instance to be
used in `execute` after it's been bound with `bind`"
  ([^Session session ^String query]
     (.prepare session query))
  ([query]
     (prepare *session* query)))

(defn bind
  "Returns a com.datastax.driver.core.BoundStatement instance to be
  used with `execute`"
  [^PreparedStatement prepared-statement values]
  (.bind prepared-statement (to-array (map codec/encode values))))


(defn ^:private execute-async
  [^Session session ^Query statement executor success error]
  (let [^ResultSetFuture rs-future (.executeAsync session statement)
        async-result (promise)]
    (Futures/addCallback
     rs-future
     (reify FutureCallback
       (onSuccess [_ result]
         (let [result (codec/result-set->maps (.get rs-future))]
           (deliver async-result result)
           (when (fn? success)
             (success result))))
       (onFailure [_ err]
         (deliver async-result err)
         (when (fn? error)
           (error err))))
     executor)
    async-result))

(defn ^:private execute-sync
  [^Session session ^Query statement]
  (codec/result-set->maps (.execute session statement)))

(defprotocol PStatement
  (query->statement [x] [x values] "Encodes input into a Statement (Query) instance"))

(extend-protocol PStatement
  Query
  (query->statement [x] x)

  String
  (query->statement [x] (SimpleStatement. x)))

(defn execute
  "Executes querys against a session. Returns a collection of rows.
The first argument can be either a Session instance or the query
directly.

So 2 signatures:

 [session query & {:keys [async? success error executor
                          consistency routing-key retry-policy
                          tracing?]
                  :or {executor default-async-executor
                       consistency *consistency*}}]

or

 [query & {:keys [async? success error executor
                  consistency routing-key retry-policy
                  tracing?]
                  :or {executor default-async-executor
                       consistency *consistency*}}]

If you chose the latter the Session must be bound with
`with-session`.

If you pass :async? true, or if you provide a :success/:error callback
this will be asynchronous, returning a promise and triggering the
handler provided if any.  Also accepts a
custom :executor (java.util.concurrent.ExecutorService instance) to be
used for the asynchronous queries."
  [& args]
  (let [[^Session session query & {:keys [async? success error executor
                                          consistency routing-key retry-policy
                                          tracing? values]
                                   :or {executor *executor*
                                        consistency *consistency*}}]
        (if (even? (count args))
          args
          (conj args *session*))
        ^Query statement (if (= PreparedStatement (type query))
                           (bind query values)
                           (query->statement query))]

    (when routing-key
      (.setRoutingKey ^SimpleStatement statement
                      ^ByteBuffer routing-key))
    (when retry-policy
      (.setRetryPolicy statement retry-policy))
    (when tracing?
      (.enableTracing statement))

    (.setConsistencyLevel statement (consistency-levels consistency))

    (if (or success async? error)
      (execute-async session statement executor success error)
      (execute-sync session statement))))
