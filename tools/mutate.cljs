(ns mutate
  "Prove the suite can fail.

  A test that has never gone red is a test nobody has measured. This applies
  one single-token mutation to one source file, runs `clojure -M:test`, and
  records which tests reddened — then restores the file. A mutation that
  reddens NOTHING is reported as a SURVIVOR, which is a finding about the
  suite, not about the mutation.

  Usage (from the repo root):

      nbb tools/mutate.cljs             # every mutation in the table
      nbb tools/mutate.cljs :duplicate  # one, by id

  The table lives in `tools/mutations.edn`. Each entry names the invariant,
  the file, an EXACT substring to find (it must occur exactly once — a
  mutation that matched a comment instead of the code would produce a red
  suite that proves nothing, which happened four times in this workspace on
  2026-08-13), and what to replace it with."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- run-tests []
  (try
    (let [out (.toString (cp/execSync "clojure -M:test 2>&1"))]
      {:exit 0 :out out})
    (catch :default e
      {:exit (or (.-status e) 1)
       :out (str (some-> (.-stdout e) .toString)
                 (some-> (.-stderr e) .toString))})))

(defn- failing-tests
  "Test names clojure.test reported as FAIL or ERROR, deduplicated."
  [out]
  (->> (re-seq #"(?:FAIL|ERROR) in \(([^)]+)\)" out)
       (map second)
       distinct
       sort
       vec))

(defn- summary-line [out]
  (or (second (re-find #"(Ran \d+ tests containing \d+ assertions\.)" out)) "no summary"))

(defn- apply-mutation! [{:keys [file find replace]}]
  (let [src (.readFileSync fs file "utf8")
        ;; JS String.split with a STRING separator — no regex, so a find
        ;; string full of parens counts what it looks like it counts.
        n (alength (.split src find))]
    (when (not= 2 n)
      (throw (ex-info (str "find string must occur exactly once in " file
                           " — occurrences: " (dec n))
                      {:file file :find find})))
    (.writeFileSync fs (str file ".orig") src)
    (.writeFileSync fs file (str/replace-first src find replace))))

(defn- restore! [{:keys [file]}]
  (.writeFileSync fs file (.readFileSync fs (str file ".orig") "utf8"))
  (.unlinkSync fs (str file ".orig")))

(defn- run-one [m]
  (println (str "\n=== " (:id m) " — " (:invariant m)))
  (apply-mutation! m)
  (let [{:keys [exit out]} (run-tests)
        reds (failing-tests out)
        summary (summary-line out)
        ran? (not= "no summary" summary)]
    (restore! m)
    (println "   " summary)
    (cond
      ;; The suite never ran. A non-zero exit alone used to score this as a
      ;; kill, which is how `:stream-seq-advances` passed for a while with an
      ;; unbalanced paren: the file stopped READING, nothing was measured,
      ;; and the tally said 57/57. A mutation that breaks the reader
      ;; demonstrates the reader. Its own outcome, and not zero.
      (not ran?)
      (do (println "    UNMEASURED — the suite did not run. The mutation broke"
                   "\n    the build rather than an invariant; fix the mutation.")
          (assoc m :unmeasured? true :survived? false :reddened []))

      (and (zero? exit) (empty? reds))
      (do (println "    SURVIVOR — no test noticed. The invariant is unmeasured.")
          (assoc m :survived? true :reddened []))

      :else
      (do (println (str "    reddened " (count reds) ":"))
          (doseq [t reds] (println (str "      - " t)))
          (assoc m :survived? false :reddened reds)))))

(defn -main [& args]
  (let [table (edn/read-string (.readFileSync fs "tools/mutations.edn" "utf8"))
        wanted (set (map #(keyword (str/replace % #"^:" "")) args))
        ms (if (seq wanted) (filterv #(wanted (:id %)) table) table)
        baseline (run-tests)]
    (println "baseline:" (summary-line (:out baseline))
             (if (zero? (:exit baseline)) "GREEN" "RED — fix before mutating"))
    (when-not (zero? (:exit baseline))
      (println (:out baseline))
      (js/process.exit 2))
    (when (empty? ms)
      (println "no mutations selected — refusing to report a pass")
      (js/process.exit 2))
    (let [results (mapv run-one ms)
          survivors (filterv :survived? results)
          unmeasured (filterv :unmeasured? results)]
      (println (str "\n=== " (count results) " mutations, "
                    (- (count results) (count survivors) (count unmeasured))
                    " killed, "
                    (count survivors) " survived, "
                    (count unmeasured) " unmeasured"))
      (doseq [s survivors] (println "  SURVIVOR:" (:id s)))
      (doseq [u unmeasured] (println "  UNMEASURED:" (:id u)))
      (js/process.exit (if (or (seq survivors) (seq unmeasured)) 1 0)))))

(apply -main *command-line-args*)
