(def project 'auth0-automation)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"resources" "src" "dev"}
          :source-paths   #{"test"}
          :dependencies   '[[org.clojure/clojure "RELEASE"]
                            [adzerk/boot-test "RELEASE" :scope "test"]
                            [camel-snake-kebab "0.4.0"]
                            [cheshire "5.8.0"]
                            [clj-http "3.7.0"]
                            [environ "1.1.0"]
                            [io.pedestal/pedestal.interceptor "0.5.3"]
                            [org.clojure/tools.cli "0.3.5"]])

(task-options!
 aot {:namespace   #{'auth0-automation.core}}
 pom {:project     project
      :version     version
      :description "A data driven way to manage an Auth0 tenant via configuration files"
      :url         "https://github.com/tomjkidd/auth0-automation"
      :scm         {:url "https://github.com/tomjkidd/auth0-automation"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:main        'auth0-automation.core
      :file        (str "auth0-automation-" version "-standalone.jar")})

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (aot) (pom) (uber) (jar) (target :dir dir))))

(deftask run
  "Run the project."
  [a args ARG [str] "the arguments for the application."]
  (require '[auth0-automation.core :as app])
  (apply (resolve 'app/-main) args))

(require '[adzerk.boot-test :refer [test]])
