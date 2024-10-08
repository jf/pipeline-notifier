#!/usr/bin/env bb

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[babashka.process :refer [exec]]
         '[babashka.fs :as fs])

(defn env [v]
  (System/getenv (str/upper-case (name v))))

;; from babashka.http-client.interceptors
(def unexceptional-statuses
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307})

(defn http-get [url & [opts]]
  "Wraps http/get to prevent 404s from throwing.
   Borrows code from babashka.http-client.interceptors."
  (let [resp (http/get url (assoc opts :throw false))]
    (if-let [status (:status resp)]
      (if (or (contains? unexceptional-statuses status)
              (= 404 status))
        resp
        (throw (ex-info (str "Exceptional status code: " status) resp)))
      resp)))

(defn get-kv-values-at [path]
  (-> (str (env :VAULT_ADDR) "/v1/" (or (env :VAULT_KV_MOUNT_PATH) "kv-v2") "/data/" path)
      (http-get {:headers {"X-Vault-Token" (env :VAULT_TOKEN)}})
      (:body)
      (json/parse-string)
      (get-in ["data" "data"])))

(def merged-secret-values
  (loop [path-components (-> (env :VAULT_KV_PATH) (str/split #"/"))
         current (first path-components)
         env {}]
    (if (empty? path-components)
      env
      (recur (rest path-components)
             (str current "/" (nth path-components 1 nil))
             (merge env (get-kv-values-at current))))))

;; special handling for kaniko (and other situations where there is no available translation for uid->username)
(defn expand-home [s]
  (if (= (System/getProperty "user.home") "?")
    (-> s
        (str/replace-first #"^~" (env :HOME))
        fs/path)
    (fs/expand-home s)))

;; given the contents of a file (as a string), write it out to a file, and return path to file
(defn env-var-2-file [content-string]
  (fs/unixify
   (if-let [bbo-header (re-find #"^#bb-ops: [^\r?\n]+" content-string)]
     (let [bbo-path (-> bbo-header
                        (subs 9)
                        str/trim
                        expand-home)
           bbo-content-string (-> (str/split content-string #"\r?\n" 2)
                                  (get 1)
                                  str)]
       (fs/create-dirs (fs/parent bbo-path))
       (-> bbo-path
           fs/create-file
           (fs/write-lines [bbo-content-string]))
       bbo-path)
     (let [_ (fs/create-dirs (fs/temp-dir))
           fs-tempfile (fs/file (fs/create-temp-file))]
       (spit fs-tempfile content-string)
       fs-tempfile))))

;; search for "*_FILE"-named env vars, write their values out to files, and replace each env var value with path to said file
(def fileified-secret-values
  (reduce-kv (fn [m k v] (assoc m k (if (re-find #".+_FILE" k)
                                      (env-var-2-file v)
                                      v)))
             {}
             merged-secret-values))

(def complete-env
  (-> (into {} (System/getenv))
      (merge fileified-secret-values)
      (dissoc "VAULT_ADDR"
              "VAULT_KV_MOUNT_PATH"
              "VAULT_KV_PATH"
              "VAULT_TOKEN")))

;; exec program with args with supplied; otherwise the practical result is to simply fileify out the *_FILE env vars
(if *command-line-args*
  (apply exec {:env complete-env} *command-line-args*)
  "")

(comment
  (http-get "http://httpstat.us/404")
  (http-get "http://httpstat.us/403")
)
