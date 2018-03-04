(ns auth0-automation.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [auth0-automation.core :refer [interceptor-pipeline run]]))

(deftest exception-test
  (testing "Test an unexpected exception is reported to the user"
    (let [exception-throwing-pipeline (conj interceptor-pipeline
                                            {:enter (fn [ctx]
                                                      (throw (ex-info "Whoops!" {:cause :whoops})))})]
      (is (= :whoops (-> (run {:suppress-output? true} exception-throwing-pipeline)
                         :auth0-automation.core/exception
                         :cause))))))

(deftest error-test
  (testing "Test a program-specific error is reported to the user"
    (let [error-pipeline (conj interceptor-pipeline
                               {:enter (fn [ctx]
                                         (assoc ctx :auth0-automation.core/errors [{:msg "Error 1"}
                                                                                   {:msg "Error 2"}]))})]
      (is (= ["Error 1" "Error 2"] (->> (run {:suppress-output? true} error-pipeline)
                                        :auth0-automation.core/errors
                                        (mapv :msg)))))))
