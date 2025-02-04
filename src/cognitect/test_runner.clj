(ns cognitect.test-runner
  (:require [clojure.tools.namespace.find :as find]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str])
  (:refer-clojure :exclude [test]))

(defn- ns-filter
  [{:keys [namespace namespace-regex]}]
  (let [regexes (or namespace-regex [#".*\-test$"])]
    (fn [ns]
      (if namespace
        (namespace ns)
        (some #(re-matches % (name ns)) regexes)))))

(defn- var-filter
  [{:keys [var include exclude]}]
  (let [test-specific (if var
                        (set (map #(or (resolve %)
                                       (throw (ex-info (str "Could not resolve var: " %)
                                                       {:symbol %})))
                                  var))
                        (constantly true))
        test-inclusion (if include
                         #((apply some-fn include) (meta %))
                        (constantly true))
        test-exclusion (if exclude
                         #((complement (apply some-fn exclude)) (meta %))
                         (constantly true))]
    #(and (test-specific %)
          (test-inclusion %)
          (test-exclusion %))))

(defn- filter-vars!
  [nses filter-fn]
  (doseq [ns nses]
    (doseq [[name var] (ns-publics ns)]
      (when (:test (meta var))
        (when (not (filter-fn var))
          (alter-meta! var #(-> %
                                (assoc ::test (:test %))
                                (dissoc :test))))))))

(defn- restore-vars!
  [nses]
  (doseq [ns nses]
    (doseq [[name var] (ns-publics ns)]
      (when (::test (meta var))
        (alter-meta! var #(-> %
                              (assoc :test (::test %))
                              (dissoc ::test)))))))
(defn test
  [options]
  (let [dirs (or (:dir options)
                 #{"test"})
        nses (->> dirs
                  (map io/file)
                  (mapcat find/find-namespaces-in-dir))
        nses (filter (ns-filter options) nses)]
    (println (format "\nRunning tests in %s" dirs))
    (dorun (map require nses))
    (try
      (filter-vars! nses (var-filter options))
      (apply test/run-tests nses)
      (finally
        (restore-vars! nses)))))

(defn- parse-kw
  [s]
  (if (.startsWith s ":") (read-string s) (keyword s)))

(defn- accumulate [m k v]
  (update-in m [k] (fnil conj #{}) v))

(def cli-options
  [["-d" "--dir DIRNAME" "Name of the directory containing tests. Defaults to \"test\"."
    :parse-fn str
    :assoc-fn accumulate]
   ["-n" "--namespace SYMBOL" "Symbol indicating a specific namespace to test."
    :parse-fn symbol
    :assoc-fn accumulate]
   ["-r" "--namespace-regex REGEX" "Regex for namespaces to test. Defaults to #\".*-test$\"\n                               (i.e, only namespaces ending in '-test' are evaluated)"
    :parse-fn re-pattern
    :assoc-fn accumulate]
   ["-v" "--var SYMBOL" "Symbol indicating the fully qualified name of a specific test."
    :parse-fn symbol
    :assoc-fn accumulate]
   ["-i" "--include KEYWORD" "Run only tests that have this metadata keyword."
    :parse-fn parse-kw
    :assoc-fn accumulate]
   ["-e" "--exclude KEYWORD" "Exclude tests with this metadata keyword."
    :parse-fn parse-kw
    :assoc-fn accumulate]
   ["-b" "--before SYMBOL" "Symbol indicating the fully qualified name of a specific function to run before tests."
    :parse-fn symbol
    :assoc-fn accumulate]
   ["-a" "--after SYMBOL" "Symbol indicating the fully qualified name of a specific function to run after tests."
    :parse-fn symbol
    :assoc-fn accumulate]
   ["-H" "--test-help" "Display this help message"]])

(defn- help
  [args]
  (println "\nUSAGE:\n")
  (println "clj -m" (namespace `help) "<options>\n")
  (println (:summary args))
  (println "\nAll options may be repeated multiple times for a logical OR effect."))

(defn before [args]
  (doseq [f (-> args :options :before)]
    ((resolve f))))

(defn after [args]
  (doseq [f (-> args :options :after)]
    ((resolve f))))

(defn -main
  "Entry point for the test runner"
  [& args]
  (let [args (parse-opts args cli-options)]
    (if (:errors args)
      (do (doseq [e (:errors args)]
            (println e))
          (help args))
      (if (-> args :options :test-help)
        (help args)
        (do (before args)
            (try
              (let [{:keys [fail error]} (test (:options args))]
                (after args)
                (System/exit (if (zero? (+ fail error)) 0 1)))
              (finally
                ;; Only called if `test` raises an exception
                (after args)
                (shutdown-agents))))))))
