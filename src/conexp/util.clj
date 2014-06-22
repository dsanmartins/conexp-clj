;; Copyright (c) Daniel Borchmann. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(in-ns 'conexp.base)

;;; def macros, inspired and partially copied from clojure.contrib.def, 1.3.0-SNAPSHOT

(defmacro def-
  "Same as def, but yields a private definition"
  [name & decls]
  (list* `def (with-meta name (assoc (meta name) :private true)) decls))

(defmacro defalias
  "Defines an alias for a var: a new var with the same root binding (if
any) and similar metadata. The metadata of the alias is its initial
metadata (as provided by def) merged into the metadata of the original."
  ([name orig]
     `(do
        (alter-meta!
         (if (.hasRoot (var ~orig))
           (def ~name (.getRawRoot (var ~orig)))
           (def ~name))
         ;; When copying metadata, disregard {:macro false}.
         ;; Workaround for http://www.assembla.com/spaces/clojure/tickets/273
         #(conj (dissoc % :macro)
                (apply dissoc (meta (var ~orig)) (remove #{:macro} (keys %)))))
        (var ~name)))
  ([name orig doc]
     (list `defalias (with-meta name (assoc (meta name) :doc doc)) orig)))

;;; Namespace tools

(defn immigrate
  "Create a public var in this namespace for each public var in the
  namespaces named by ns-names. The created vars have the same name, root
  binding, and metadata as the original except that their :ns metadata
  value is this namespace.

  This function is literally copied from the clojure.contrib.ns-utils library."
  [& ns-names]
  (doseq [ns ns-names]
    (require ns)
    (doseq [[sym, ^clojure.lang.Var var] (ns-publics ns)]
      (let [sym (with-meta sym (assoc (meta var) :ns *ns*))]
        (if (.hasRoot var)
          (intern *ns* sym (.getRawRoot var))
          (intern *ns* sym))))))

(immigrate 'clojure.set
           'clojure.core.incubator
           'clojure.math.numeric-tower)

;;; Version

(def- internal-version-string
  (.trim #=(slurp "VERSION")))

(def- conexp-version-map
  (let [[_ major minor patch qualifier] (re-find #"(\d+)\.(\d+)\.(\d+)-(.+)" internal-version-string)]
    {:major (Integer/parseInt major),
     :minor (Integer/parseInt minor),
     :patch (Integer/parseInt patch),
     :qualifier qualifier}))

(defn conexp-version
  "Returns the version of conexp as a string."
  []
  (let [{:keys [major minor patch qualifier]} conexp-version-map]
    (str major "." minor "." patch "-" qualifier )))

(defn has-version?
  "Compares given version of conexp and returns true if and only if
  the current version of conexp is higher or equal than the given one"
  [{my-major :major, my-minor :minor, my-patch :patch}]
  (assert (and my-major my-minor my-patch))
  (let [{:keys [major, minor, patch]} conexp-version-map]
    (or (and (< my-major major))
        (and (= my-major major)
             (< my-minor minor))
        (and (= my-major major)
             (= my-minor minor)
             (< my-patch patch)))))

;;;

(defn quit
  "Quits conexp-clj."
  []
  (System/exit 0))

;;; Testing

(require 'clojure.test)

(defn test-conexp
  "Runs tests for conexp. If with-contrib? is given and true, tests
  conexp.contrib.tests too."
  ([] (test-conexp false))
  ([with-contrib?]
     (if with-contrib?
       (do (require 'conexp.tests
                    'conexp.contrib.tests)
           (clojure.test/run-tests 'conexp.tests
                                   'conexp.contrib.tests))
       (do (require 'conexp.tests)
           (clojure.test/run-tests 'conexp.tests)))))

(defmacro tests-to-run
  "Defines tests to run when the namespace in which this macro is
  called is tested by test-ns.  Additionally, runs all tests in the
  current namespace, before all other tests in supplied as arguments."
  [& namespaces]
  `(defn ~'test-ns-hook []
     (clojure.test/test-all-vars '~(ns-name *ns*))
     (doseq [ns# '~namespaces]
       (let [result# (do (require ns#) (clojure.test/test-ns ns#))]
         (dosync
          (ref-set clojure.test/*report-counters*
                   (merge-with + @clojure.test/*report-counters* result#)))))))

(defmacro with-testing-data
  "Expects for all bindings the body to be evaluated to true. bindings
  must be those of doseq."
  [bindings & body]
  `(doseq ~bindings
     ~@(map (fn [expr]
              `(let [result# (try
                               (do ~expr)
                               (catch Throwable e#
                                 (println "Caught exception:" e#)
                                 false))]
                 (if-not result#
                   ~(let [vars (vec (remove keyword? (take-nth 2 bindings)))]
                      `(do (println "Test failed for" '~vars "being" ~vars)
                           (clojure.test/is false)))
                   (clojure.test/is true))))
            body)))

;;; Types

(def clojure-set clojure.lang.PersistentHashSet)
(def clojure-fn  clojure.lang.Fn)
(def clojure-seq clojure.lang.Sequential)
(def clojure-vec clojure.lang.PersistentVector)
(def clojure-map clojure.lang.Associative)
(def clojure-coll clojure.lang.IPersistentCollection)

(defn clojure-type
  "Dispatch function for multimethods."
  [thing]
  (class thing))


;;; Technical Helpers

(defn proper-subset?
  "Returns true iff set-1 is a proper subset of set-2."
  [set-1 set-2]
  (and (not= set-1 set-2)
       (subset? set-1 set-2)))

(defn proper-superset?
  "Returns true iff set-1 is a proper superset of set-2."
  [set-1 set-2]
  (proper-subset? set-2 set-1))

(defn singleton?
  "Returns true iff given thing is a singleton sequence or set."
  [x]
  (and (or (set? x) (sequential? x))
       (not (empty? x))
       (not (next x))))

(defn ensure-length
  "Fills given string with padding to have at least the given length."
  ([string length]
     (ensure-length string length " "))
  ([string length padding]
     (apply str string (repeat (- length (count string)) padding))))

(defn with-str-out
  "Returns string of all output being made in (flatten body)."
  [& body]
  (with-out-str
    (doseq [element (flatten body)]
      (print element))))

(defn- compare-order
  "Orders things for proper output of formal contexts."
  [x y]
  (if (and (= (class x) (class y))
           (instance? Comparable x))
    (> 0 (compare x y))
    (> 0 (compare (str (class x)) (str (class y))))))

(defn sort-by-second
  "Ensures that pairs are ordered by second entry first. This gives
  better output for context sums, products, ..."
  [x y]
  (cond
    (and (vector? x)
         (vector? y)
         (= 2 (count x) (count y)))
    (if (= (second x) (second y))
      (compare-order (first x) (first y))
      (compare-order (second x) (second y)))
    :else
    (compare-order x y)))

(defn sort-by-first
  "Ensures that pairs are ordered by first entry first."
  [x y]
  (cond
    (and (vector? x)
         (vector? y)
         (= 2 (count x) (count y)))
    (if (= (first x) (first y))
      (compare-order (second x) (second y))
      (compare-order (first x) (first y)))
    :else
    (compare-order x y)))

(defn zip
  "Returns sequence of pairs [x,y] where x runs through seq-1 and
  y runs through seq-2 simultaneously. This is the same as
  (map #(vector %1 %2) seq-1 seq-2)."
  [seq-1 seq-2]
  (map #(vector %1 %2) seq-1 seq-2))

(defn first-non-nil
  "Returns first non-nil element in seq, or nil if there is none."
  [seq]
  (loop [seq seq]
    (when seq
      (let [elt (first seq)]
        (or elt (recur (next seq)))))))

(defn split-at-first
  "Splits given sequence at first element satisfing predicate.
  The first element satisfing predicate will be in the second sequence."
  [predicate sequence]
  (let [index (or (first-non-nil
                   (map #(if (predicate %1) %2)
                        sequence (iterate inc 0)))
                  (count sequence))]
    (split-at index sequence)))

(defn split-at-last
  "Splits given sequence at last element satisfing predicate.
  The last element satisfing predicate will be in the first sequence."
  [predicate sequence]
  (let [index (or (first-non-nil
                   (map #(if (predicate %1) %2)
                        (reverse sequence) (range (count sequence) 0 -1)))
                  0)]
    (split-at index sequence)))

(defn warn
  "Emits a warning message on *out*."
  [message]
  (println "WARNING:" message))

(defmacro die-with-error
  "Stops program by raising the given error with strings as message."
  [^Class error, strings]
  `(throw (new ~error ^String (apply str ~strings))))

(defn illegal-argument
  "Throws IllegalArgumentException with given strings as message."
  [& strings]
  (die-with-error IllegalArgumentException strings))

(defn unsupported-operation
  "Throws UnsupportedOperationException with given strings as message."
  [& strings]
  (die-with-error UnsupportedOperationException strings))

(defn not-yet-implemented
  "Throws UnsupportedOperationException with \"Not yet implemented\"
  message."
  []
  (unsupported-operation "Not yet implemented"))

(defn illegal-state
  "Throws IllegalStateException with given strings as message."
  [& strings]
  (die-with-error IllegalStateException strings))

(defmacro with-altered-vars
  "Executes the code given in a dynamic environment where the var
  roots of the given names are altered according to the given
  bindings. The bindings have the form [name_1 f_1 name_2 f_2 ...]
  where f_i is applied to the original value of the var associated
  with name_i to give the new value which will be in place during
  execution of body. The old value will be restored after execution
  has been finished."
  [bindings & body]
  (when-not (even? (count bindings))
    (illegal-argument "Bindings must be given pairwise."))
  (let [bindings (partition 2 bindings),
        bind-gen (for [[n _] bindings] [(gensym) n])]
    `(let ~(vec (mapcat (fn [[name thing]] [name `(deref (var ~thing))])
                        bind-gen))
       (try
         ~@(map (fn [[thing f]]
                  `(alter-var-root (var ~thing) ~f))
                bindings)
         ~@body
         (finally
          ~@(map (fn [[name thing]]
                   `(alter-var-root (var ~thing) (constantly ~name)))
                 bind-gen))))))

(defmacro with-var-bindings
  "Executes body with the vars in bindings set to the corresponding
  values."
  [bindings & body]
  (when-not (even? (count bindings))
    (illegal-argument "Bindings must be given pairwise."))
  `(with-altered-vars ~(vec (mapcat (fn [[a b]] `[~a (constantly ~b)])
                                   (partition 2 bindings)))
     ~@body))

(defmacro with-memoized-fns
  "Runs code in body with all given functions memoized."
  [functions & body]
  `(with-altered-vars ~(vec (mapcat (fn [f] [f `memoize]) functions))
     ~@body))

(defmacro memo-fn
  "Defines memoized, anonymous function."
  [name args & body]
  `(let [cache# (atom {})]
     (fn ~name ~args
       (if (contains? @cache# ~args)
         (@cache# ~args)
         (let [rslt# (do ~@body)]
           (swap! cache# assoc ~args rslt#)
           rslt#)))))

(defn inits
  "Returns a lazy sequence of the beginnings of sqn."
  [sqn]
  (let [runner (fn runner [init rest]
                 (if (not rest)
                   [init]
                   (lazy-seq
                     (cons init (runner (conj init (first rest))
                                        (next rest))))))]
    (runner [] (seq sqn))))

(defn tails
  "Returns a lazy sequence of the tails of sqn."
  [sqn]
  (let [runner (fn runner [rest]
                 (if (not rest)
                   [[]]
                   (lazy-seq
                     (cons (vec rest) (runner (next rest))))))]
    (runner (seq sqn))))

(import 'java.util.Calendar
        'java.text.SimpleDateFormat)

(defn now
  "Returns the current time in a human readable format."
  []
  (let [^Calendar cal (Calendar/getInstance),
        ^SimpleDateFormat sdf (SimpleDateFormat. "HH:mm:ss yyyy-MM-dd")]
    (.format sdf (.getTime cal))))

(defn ask
  "Performs simple quering. prompt is printed first and then the user
  is asked for an answer (via read). The other arguments are
  predicates with corresponding error messages. If a given answer does
  not satisfy some predicate pred, it's associated error message is
  printed (if it is a string) or it is assumed to be a function of one
  argument, whereupon it is called with the invalid answer and the
  result is printed. In any case, the user is asked again, until the
  given answer fulfills all given predicates."
  [prompt read & preds-and-fail-messages]
  (when-not (even? (count preds-and-fail-messages))
    (illegal-argument "Every predicate needs to have a corresponding error message."))
  (let [predicates (partition 2 preds-and-fail-messages),
        sentinel   (Object.),
        read-fn    #(try (read) (catch Throwable _ sentinel))]
    (do
      (print prompt)
      (flush)
      (loop [answer (read-fn)]
        (if-let [fail (if (identical? answer sentinel)
                        "An error occured while reading.\n",
                        (first-non-nil (map #(when-not ((first %) answer)
                                               (second %))
                                            predicates)))]
          (do
            (if (string? fail)
              (print fail)
              (print (fail answer)))
            (flush)
            (recur (read-fn)))
          answer)))))

(defn yes-or-no?
  "Asks string, expecting 'yes' or 'no'. Returns true when answered
  'yes' and false otherwise."
  [question]
  (= 'yes (ask (str question)
               #(read-string (str (read-line)))
               #{'yes 'no}
               "Please answer 'yes' or 'no': ")))

;;; deftype utilities

(defmacro generic-equals
  "Implements a generic equals for class on fields."
  [[this other] class fields]
  `(boolean (or (identical? ~this ~other)
                (when (= (class ~this) (class ~other))
                  (and ~@(map (fn [field]
                                `(= ~field (. ~(vary-meta other assoc :tag class) ~field)))
                              fields))))))

(defn hash-combine-hash
  "Combines the hashes of all things given."
  [& args]
  (reduce #(hash-combine %1 (hash %2)) 0 args))


;;; Math

(defmacro =>
  "Implements implication."
  [a b]
  `(if ~a ~b true))

(defn <=>
  "Implements equivalence."
  [a b]
  (or (and a b)
      (and (not a) (not b))))

(defn- expand-bindings
  "Expands bindings used by forall and exists."
  [bindings body]
  (if (empty? bindings)
    body
    (let [[x xs] (first bindings)]
      `(loop [ys# ~xs]
         (if (empty? ys#)
           true
           (let [~x (first ys#)]
             (and ~(expand-bindings (rest bindings) body)
                  (recur (rest ys#)))))))))

(defmacro forall
  "Implements logical forall quantor. Bindings is of the form [var-1
  seq-1 var-2 seq-2 ...]. Returns boolean value."
  [bindings condition]
  (when-not (let [c (count bindings)]
              (and (<= 0 c)
                   (zero? (mod c 2))))
    (illegal-argument "forall requires even number of bindings."))
  `(boolean ~(expand-bindings (map vec (partition 2 bindings)) condition)))

(defmacro exists
  "Implements logical exists quantor. Bindings is of the form [var-1
  seq-1 var-2 seq-2 ...]. Returns boolean value."
  [bindings condition]
  (when-not (let [c (count bindings)]
              (and (<= 0 c)
                   (zero? (mod c 2))))
    (illegal-argument "exists requires even number of bindings."))
  `(not ~(expand-bindings (map vec (partition 2 bindings)) `(not ~condition))))

(defmacro set-of
  "Macro for writing sets as mathematicians do (at least similar to
  it.) The following syntax constructions are supported:

    (set-of x [x [1 2 3]])
      for the set of all x with x in [1 2 3]

    (set-of x | x [1 2 3])
      for the same set

    (set-of [x y] [x [1 2 3], y [4 5 6] :when (= 1 (gcd x y))])
      for the set of all pairs [x y] with x in [1 2 3], y in [4 5 6]
      and x and y are coprime.

  In general, the condition vector (or the sequence after |) must be
  suitable for doseq."
  [thing & condition]
  (let [condition (if (= '| (first condition))
                    (vec (rest condition))
                    (vec (first condition)))]
    `(let [result# (atom (transient #{}))]
       (doseq ~condition
         (swap! result# conj! ~thing))
       (persistent! @result#))))

(defmacro sum
  "Computes the sum of expr for indices from start to end, named as
  index."
  [index start end & expr]
  `(reduce (fn [sum# ~index]
             (+ sum#
                (do ~@expr)))
           0N
           (range ~start (inc ~end))))

(defmacro prod
  "Computes the product of expr for indices from start to end, named
  as index."
  [index start end & expr]
  `(reduce (fn [prod# ~index]
             (* prod#
                (do ~@expr)))
           1N
           (range ~start (inc ~end))))

(defn div
  "Integer division."
  [a b]
  (/ (- a (mod a b)) b))

(defn expt
  "Exponentiation of arguments. Is exact if given arguments are exact
  and returns double otherwise."
  [a b]
  (cond
   (not (and (integer? a) (integer? b)))
   (clojure.math.numeric-tower/expt a b),

   (< b 0)
   (/ (expt a (- b))),

   :else
   (loop [result 1N,
          aktpot (bigint a),
          power  (bigint b)]
     (if (zero? power)
       result
       (recur (if (zero? (mod power 2))
                result
                (* result aktpot))
              (* aktpot aktpot)
              (div power 2))))))

(defn distinct-by-key
  "Returns a sequence of all elements of the given sequence with distinct key values,
  where key is a function from the elements of the given sequence. If two elements
  correspond to the same key, the one is chosen which appeared earlier in the sequence.

  This function is copied from clojure.core/distinct and adapted for using a key function."
  [sequence key]
  (let [step (fn step [xs seen]
               (lazy-seq
                 ((fn [xs seen]
                    (when-let [s (seq xs)]
                      (let [f     (first xs)
                            key-f (key f)]
                        (if (contains? seen key-f)
                          (recur (rest s) seen)
                          (cons f (step (rest s) (conj seen key-f)))))))
                  xs seen)))]
    (step sequence #{})))

(defn reduce!
  "Does the same as reduce, but calls transient on the initial value
  and persistent! on the result."
  [fn initial-value coll]
  (persistent! (reduce fn (transient initial-value) coll)))

(defn map-by-fn
  "Returns a hash map with the values of keys as keys and their values
  under function as values."
  [function keys]
  (reduce! (fn [map k]
             (assoc! map k (function k)))
           {}
           keys))

(defmacro with-printed-result
  "Prints string followed by result, returning it."
  [string & body]
  `(let [result# (do
                   ~@body)]
     (println ~string result#)
     result#))

(defn ensure-seq
  "Given anything that can be made a sequence from, returns that
  sequence. If given a number x, returns (range x)."
  [x]
  (cond
   (number? x) (range x),
   (or (nil? x)
       (instance? java.util.Collection x)
       (.isArray ^Class (class x)))
   (or (seq x) ())
   :otherwise
   (illegal-argument "Cannot generate meaningful sequence from " x ".")))

(defn topological-sort
  "Returns a linear extension of the given collection coll and the
  supplied comparator comp."
  [comp coll]
  (let [dependencies (map-by-fn (fn [x]
                                  (set (filter #(and (comp % x)
                                                     (not= % x))
                                               coll)))
                                coll)]
    (loop [dependencies dependencies,
           sorted       (transient [])]
      (if (empty? dependencies)
        (persistent! sorted)
        (let [nexts (set (filter #(empty? (dependencies %))
                                 (keys dependencies)))]
          (if (empty? nexts)
            (illegal-argument "Cyclic comparator given.")
            (recur (reduce (fn [map x]
                             (if (contains? nexts x)
                               (dissoc map x)
                               (update-in map [x] difference nexts)))
                           dependencies
                           (keys dependencies))
                   (reduce conj! sorted nexts))))))))


;;; Searching for minimum covers

(defn- covers?
  "Technical Helper. Tests wheterh all elements in base-set are contained at least one set in sets."
  [sets base-set]
  (if (empty? base-set)
    true
    (loop [rest (transient base-set),
           sets sets]
      (if-not sets
        false
        (let [new-rest (reduce disj! rest (first sets))]
          (if (zero? (count new-rest))
            true
            (recur new-rest (next sets))))))))

(defn- redundant?
  "Technical Helper. For a given set base-set, a collection cover of sets and a map mapping elements
  from base-set to the number of times they occur in sets in cover, tests whether the cover is
  redundant or not, i.e. if a proper subcollection of cover is already a cover or not."
  [base-set cover count]
  (exists [set cover]
    (forall [x set]
      (=> (contains? base-set x)
          (<= 2 (get count x))))))

(defn minimum-set-covers
  "For a given set base-set and a collection of sets returns all subcollections of sets such that
  the union of the contained sets cover base-set and that are minimal with that property."
  [base-set sets]
  (let [result  (atom []),
        search  (fn search [rest-base-set current-cover cover-count sets]
                  (cond
                   (redundant? base-set current-cover cover-count)
                   nil,

                   (empty? rest-base-set)
                   (swap! result conj current-cover),

                   (empty? sets)
                   nil,

                   :else
                   (when (covers? sets rest-base-set)
                     (let [counts (map-by-fn #(count (intersection rest-base-set %))
                                             sets),
                           sets   (sort #(- (counts %2) (counts %1)) ; bah
                                        sets)],
                       (search (difference rest-base-set (first sets))
                               (conj current-cover (first sets))
                               (reduce! (fn [map x]
                                          (if (contains? base-set x)
                                            (assoc! map x (inc (get map x)))
                                            map))
                                        cover-count
                                        (first sets))
                               (rest sets))
                       (search rest-base-set
                               current-cover
                               cover-count
                               (rest sets))))))]
    (search base-set
            #{}
            (map-by-fn (constantly 0) base-set)
            sets)
    @result))

;;;

nil
