(ns auth0-automation.core
  (:require [auth0-automation.auth0 :as auth0]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [environ.core :refer [env]]
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
                  (pprint ctx))))
            ctx)})

(def env-config
  "A convenient representation of the environment variables that are needed"
  {:auth0 {:domain        (env :auth0-automation-domain)
           :client-id     (env :auth0-automation-client-id)
           :client-secret (env :auth0-automation-client-secret)}
   :edn-config {:filepath (env :auth0-automation-edn-config-filepath)}})

(def provide-env-config
  "Adds `env-config` to the context."
  {:enter (fn [ctx]
            (assoc ctx :env-config env-config))})

(def read-edn-configuration
  "Reads the `edn-configuration` file that describes the desired Auth0 environment"
  {:enter (fn [{:keys [env-config] :as ctx}]
            (let [filepath (get-in env-config [:edn-config :filepath])]
              (if (and (some? filepath) (.exists (io/as-file filepath)))
                (assoc ctx :edn-config (edn/read-string(slurp filepath)))
                (assoc ctx ::errors [{:type :edn-config-does-not-exist
                                      :msg  "Was unable to locate an edn-config file. Make sure the filepath points to an actual file."}]))))})

(def get-auth0-token
  "Attempts to get an Auth0 Management API token"
  {:enter (fn [{:keys [dryrun? env-config] :as ctx}]
            (if dryrun?
              ctx
              (if-let [token (auth0/get-token env-config)]
                (assoc ctx ::auth0-token token)
                (assoc ctx ::errors [{:type :unable-to-get-auth0-token
                                      :msg "Unable to get Auth0 token. Make sure that the domain, client-id, and client-secret are correct and that the Auth0 service is working."}]))))})

(def determine-api-actions
  "Create a data structure that represents the actions that are needed to create
  the desired Auth0 environment based on the current one."
  {:enter (fn [ctx]
            (assoc ctx ::api-actions []))})

(def transact-api-actions!
  "Performs the actions identified by `::api-actions` against the Auth0 environment"
  {:enter (fn [{:keys [dryrun?] :as ctx}]
            (if dryrun?
              ctx
              (assoc ctx ::api-call-responses [])))})

(def report-results
  {:enter (fn [{:keys [suppress-output? verbose?] :as ctx}]
            (when-not suppress-output?
              (println "Successfully configured the Auth0 environment")
              (when verbose?
                (pprint (dissoc ctx
                                :io.pedestal.interceptor.chain/execution-id
                                :io.pedestal.interceptor.chain/queue
                                :io.pedestal.interceptor.chain/stack
                                :io.pedestal.interceptor.chain/terminators))))
            ctx)})

(def interceptor-pipeline
  "The pipeline of interceptors used to perform the Auth0 environment update"
  [exception-interceptor
   errors-interceptor
   provide-env-config
   read-edn-configuration
   get-auth0-token
   determine-api-actions
   transact-api-actions!
   report-results])

(defn run
  "Perform the Auth0 environment update to match the desired configuration"
  ([opts]
   (run opts interceptor-pipeline))
  ([opts pipeline]
   (interceptor-chain/execute opts pipeline)))

(defn args->opts
  "Turn command-line arguments into an opts hashmap, used to control program behavior"
  [args]
  ;;TODO: Pay attention to args to create options map
  {:verbose? true})

(defn -main
  "This program assumes no other users are changing the current Auth0 environment,
  and makes no attempt to detect simultaneous use. Make sure no one else is updating
  the environment while running this script!"
  [& args]
  (run (args->opts args)))
