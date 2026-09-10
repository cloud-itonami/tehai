(ns tehai.edge.endpoints
  "The HTTP surface tehai exposes — exactly one route:

      POST /api/invoice/draft   draft an invoice for a project

  and nothing else, permanently. Per `manifest/repository-rules.edn` an
  itonami actor is `:on-demand`: it answers a request and stops.

  ## Why drafting and never issuing

  This is the sharpest instance of the fleet invariant that no actor
  auto-drives another actor's own governed lifecycle. Of tehai's ops:

    :draft-invoice   auto-commits when clean; locks no hours    → exposed
    :issue-invoice   ALWAYS escalates; money leaves the system  → not exposed
    :assign-person   commits a staffing decision                → not exposed
    :report-margin   reads a client's cost structure            → not exposed

  Drafting is safe to expose precisely because a draft is not a
  commitment: only `:issue-invoice` adds entry keys to the billed set,
  so a caller hammering this route produces drafts and never bills an
  hour. Issuing has no HTTP representation at all — an invoice reaches a
  client because a person resumed the thread, and there is no request
  that can substitute for that.

  `:report-margin` is withheld for a different reason. Margin exposes
  cost rates, which is the firm's own commercial position rather than
  the client's, and an endpoint that returns it is one credential away
  from being a competitor's.

  ## Two gates, and neither is optional

  1. CACAO signature + temporal window (`cacao.edge.verify`; not
     reimplemented here — ADR-2607268000).
  2. The verified caller DID must be on the allow-list, which maps DID →
     client id. As a map rather than a set, it is what stops a signed
     caller drafting against another client's project before the
     governor's `:project-wrong-client` hold ever runs.

  **An absent allow-list serves 503, never an open endpoint.**"
  (:require [tehai.actor :as actor]
            [tehai.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:cljs [cacao.edge.verify :as cacao])))

(defn parse-allowlist
  "`\"did:key:z6Mk…=c-1\"` -> `{did client-id}`, or nil when absent,
  blank or wholly malformed."
  [s]
  (when (and (string? s) (seq (.trim s)))
    (let [pairs (keep (fn [entry]
                        (let [[did client] (map #(.trim %) (.split entry "="))]
                          (when (and did client (seq did) (seq client))
                            [did client])))
                      (.split (.trim s) ","))]
      (when (seq pairs) (into {} pairs)))))

(defn client-for [allowlist did] (get allowlist did))

(defn parse-body
  "EDN request body -> `{:project str :invoice-id str}`. Read with
  `clojure.edn/read-string`, which evaluates nothing."
  [s]
  (try
    (let [m (edn/read-string s)]
      (when (and (map? m) (string? (:project m)) (seq (:project m)))
        m))
    (catch #?(:clj Exception :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; Store selection
;; ---------------------------------------------------------------------------

(defn store-mode
  "How this deployment is configured to store what it accepts, from the
  `TEHAI_STORE` env var.

    nil          nothing configured
    :ephemeral   an in-process store that does not survive the request

  Returns nil for anything else, including an unrecognised value — a typo
  in a deployment variable must not silently select a storage mode.

  Portable (takes a plain map) so the decision is testable without a
  platform; the `:cljs` entry point converts Cloudflare's `env` into one."
  [env]
  (case (some-> (get env "TEHAI_STORE") .trim)
    "ephemeral" :ephemeral
    nil))

(defn store-unconfigured-response
  "What to serve when no store mode is configured.

  Deliberately 503 and NOT an empty in-process store. An empty store makes
  every request fail the governor's registration check, so the caller is
  told `:no-worker` — blamed for a deployment that has no store at all.
  Misattributed blame is worse than a refusal: the operator goes looking
  at their own registration while the actual fault is here."
  []
  {:status 503
    :body {:ok false :error "no store configured"
           ;; One line. A multi-line string literal here leaks the source
           ;; file's indentation into the JSON body, which the release
           ;; build made visible the first time this ran for real.
           :hint (str "bind a durable store, or set TEHAI_STORE=ephemeral"
                      " for a non-persisting smoke test")}})

(defn draft-invoice-core!
  "`POST /api/invoice/draft`. `caller-did` is already verified.

    503  no allow-list configured
    403  caller not on the allow-list
    400  unparseable body or no project named
    409  the governor held it, with the violations
    200  drafted. The response carries the total, the lines, and what the
         draft could NOT bill — a draft that reported only its total
         would be a draft whose gaps are invisible until someone
         reconciles the month."
  [store mode allowlist caller-did raw-body]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (if-let [body (parse-body raw-body)]
      (let [client-id (client-for allowlist caller-did)
            g (actor/build-graph {:store store})
            r (actor/run-request! g {:client-id client-id :op :draft-invoice
                                     :project (:project body)
                                     :invoice-id (or (:invoice-id body) "draft")}
                                  {} (str "edge-" caller-did "-" (:project body)))
            disposition (get-in r [:state :disposition])
            proposal (get-in r [:state :proposal])]
        (if (= :commit disposition)
          {:status 200
           :body {:ok true :ephemeral (= :ephemeral mode) :client client-id :project (:project body)
                  :invoices-on-record (count (store/invoices store))
                  :total (:total proposal)
                  :lines (:lines proposal)
                  :entries (count (:entry-keys proposal))
                  ;; :unknown travels as-is rather than being coerced to a
                  ;; number — see kotoba.psa invariant 2.
                  :margin (:margin proposal)
                  :note (:rationale proposal)
                  :issued false}}
          {:status 409
           :body {:ok false :disposition disposition
                  :violations (mapv #(select-keys % [:rule :detail])
                                    (get-in r [:state :verdict :violations]))}}))
      {:status 400 :body {:ok false :error "invalid request body"}})))

#?(:cljs
   (defn- json-response [{:keys [status body]}]
     (js/Response. (js/JSON.stringify (clj->js body))
                   #js {:status status
                        :headers #js {"content-type" "application/json"}})))

#?(:cljs
   (defn on-request-post-draft
     "Parses the Cloudflare `context`, verifies the CACAO, and hands an
     already-verified caller to `draft-invoice-core!`. No policy of its
     own."
     [context]
     (let [env (aget context "env")
           mode (store-mode {"TEHAI_STORE" (aget env "TEHAI_STORE")})
           allowlist (parse-allowlist (aget env "TEHAI_CALLER_ALLOWLIST"))
           header (or (.get (aget (aget context "request") "headers") "authorization") "")
           token (if (.startsWith header "Bearer ") (subs header 7) header)]
       (-> (js/Promise.all #js [(cacao/verify token) (.text (aget context "request"))])
           (.then (fn [results]
                    (let [v (aget results 0) raw (aget results 1)]
                      (cond
                        (nil? mode)
                        (json-response (store-unconfigured-response))

                        (not (:valid v))
                        (json-response {:status 401
                                                        :body {:ok false :error "invalid or expired CACAO"}})

                        :else
                        (json-response (draft-invoice-core! (store/mem-store) mode
                                                      allowlist (:iss v) raw))))))
           (.catch (fn [e]
                     (json-response {:status 500
                                     :body {:ok false :error "request failed"
                                            :reason (ex-message e)}})))))))
