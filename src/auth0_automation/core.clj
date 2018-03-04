(ns auth0-automation.core
  (:require [clojure.pprint :refer [pprint]]
            [io.pedestal.interceptor.chain :as interceptor-chain])
  (:gen-class))

(def exception-interceptor
  "If an unexpected exception occurs during the process, capture it in `::exception`.

  Will also print it to console if `suppress-output?` is not true"
  {:error (fn exception-catcher [{:keys [suppress-output? verbose?] :as ctx} ex]
            (when-not suppress-output?
              (println "Unexpected exception occured.")
              (if verbose?
                (pprint {:ctx ctx
                         :ex  ex})
                (pprint ex)))
            (assoc ctx ::exception (ex-data ex)))})

(defn- display-error-msg
  [{:keys [msg]}]
  (when msg
    (println "Error msg: " msg)))

(def errors-interceptor
  "Ensures that this program's errors can be defined and respected as part of the interceptor chain.
  If any expected errors occurs during the process, capture them in `::errors`.

  Will also print it to console if `suppress-output?` is not true"
  {:enter (fn register-error-terminator [ctx]
            (interceptor-chain/terminate-when ctx ::errors))
   :leave (fn report-error [{:keys [suppress-output? verbose?] :as ctx}]
            (when-not suppress-output?
              (when-let [errs (::errors ctx)]
                (println "Error(s) occured.")
                (doseq [err errs]
                  (display-error-msg err))
                (when verbose?
                  (pprint {:ctx ctx}))))
            ctx)})

(def init-context
  "Initialize the context for the program. This includes option configuration
  to control how errors are reported."
  {:enter (fn [ctx]
            (assoc ctx
                   :verbose? true
                   :suppress-output? true
                   :dryrun? true))})

(def read-configuration
  "Reads the configuration file that describes the desired Auth0 environment"
  {:enter (fn [ctx]
            (assoc ctx ::auth0-config {:tbd "TBD"}))})

(def determine-api-calls
  "Create a data structure that represents the actions that are needed to create
  the desired Auth0 environment based on the current one."
  {:enter (fn [ctx]
            (assoc ctx ::api-calls []))})

(def transact-api-calls!
  "Performs the actions identified by `::api-calls` against the Auth0 environment"
  {:enter (fn [{:keys [dryrun?] :as ctx}]
            (if dryrun?
              ctx
              (assoc ctx ::api-call-responses [])))})

(def interceptor-pipeline
  "The pipeline of interceptors used to perform the Auth0 environment update"
  [exception-interceptor
   errors-interceptor
   init-context
   read-configuration
   determine-api-calls
   transact-api-calls!])

(defn run
  "Perform the Auth0 environment update to match the desired configuration"
  ([args]
   (run args interceptor-pipeline))
  ([args pipeline]
   ;; TODO: Use args and/or environment for configuration
   (interceptor-chain/execute {} pipeline)))

(defn -main
  "This program assumes no other users are changing the current Auth0 environment,
  and makes no attempt to detect simultaneous use. Make sure no one else is updating
  the environment while running this script!"
  [& args]
  (run args))
