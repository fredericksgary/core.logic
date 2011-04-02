(ns logos.disequality
  (:refer-clojure :exclude [reify == inc])
  (:use logos.minikanren
        logos.match)
  (:import [logos.minikanren Substitutions Pair]))

;; =============================================================================
;; Utilities

;; TODO: change to lazy-seq
(defn prefix [s <s]
  (if (= s <s)
    ()
    (cons (first s) (prefix (rest s) <s))))

;; =============================================================================
;; Verification

(declare make-store)
(declare merge-constraint)
(declare make-c)
(declare propagate)
(declare get-simplified)
(declare discard-simplified)
(declare constraint-verify)

(defn ^Substitutions constraint-verify-simple [^Substitutions s u v]
  (let [uc (set (map #(walk s %) (constraints u)))]
   (if (contains? uc v)
     nil
     (let [u (remove-constraints u)
           v (if (lvar? v) (add-constraints v uc) v)]
       (make-s (-> (.s s) (dissoc u) (assoc u v))
               (cons (pair u v) (.l s))
               constraint-verify
               (.cs s))))))

(defn ^Substitutions constraint-verify [^Substitutions s u v]
  (when-let [s (constraint-verify-simple s u v)]
    (if-let [cs (.cs s)]
      (let [cs (propagate cs s u v)
            s (reduce (fn [s [u c]]
                        (constrain s u c))
                      s (get-simplified cs))
            s (make-s (.s s) (.l s)
                      constraint-verify (discard-simplified cs))]
        (constraint-verify-simple s (get-var s u) v))
      s)))

(defprotocol IDisequality
  (!=-verify [this sp]))

(defn unify* [^Substitutions s m]
  (loop [[[u v :as p] & r] (seq m) result {}]
    (let [^Substitutions s' (unify s u v)]
      (cond
       (nil? p) result
       (not s') nil
       (identical? s s') (recur r result)
       :else (recur r
                    (conj result
                          (first (prefix (.l s') (.l s)))))))))

(extend-type Substitutions
  IDisequality
  (!=-verify [this sp]
             (let [^Substitutions sp sp]
               (cond
                (not sp) this
                (= this sp) nil
                :else (let [[[u v] :as c] (prefix (.l sp) (.l this))
                            simple (= (count c) 1)]
                        (if simple
                          (let [u (walk this u)
                                v (walk this v)]
                            (if (= u v)
                              nil
                              (let [s (.s this)
                                    u (add-constraint u v)
                                    s (if (contains? s u)
                                        (let [uv (s u)]
                                          (-> s (dissoc u) (assoc u uv)))
                                        (assoc s u unbound))
                                    v (if (lvar? v) (add-constraint v u) v)
                                    s (if (contains? s v)
                                        (let [vv (s v)]
                                          (-> s (dissoc v) (assoc v vv)))
                                        (assoc s v unbound))]
                                (make-s s (.l this) constraint-verify (.cs this)))))
                          (let [r (unify* this c)]
                            (cond
                             (nil? r) this
                             (empty? r) nil
                             :else (make-s (.s this) (.l this) constraint-verify
                                           (-> (or (.cs this) (make-store))
                                               (merge-constraint
                                                (make-c r))))))))))))

;; =============================================================================
;; Constraint

;; need hashCode and equals for propagation bit
(deftype Constraint [^String name m okeys hash]
  Object
  (equals [this o]
          (and (instance? Constraint o)
               (let [^Constraint o o]
                 (identical? name (.name o)))))
  (hashCode [_] hash)
  clojure.lang.Associative
  (containsKey [this key]
               (contains? m key))
  (entryAt [this key]
           (.entryAt m key))
  clojure.lang.IPersistentMap
  (without [this key]
           (Constraint. name (dissoc m key) okeys hash))
  clojure.lang.ISeq
  (first [_] (first m))
  (seq [_] (seq m))
  (count [_] (count m))
  clojure.lang.ILookup
  (valAt [this key]
         (m key)))

;; NOTE: gensym is slow don't use it directly
(defn ^Constraint make-c [m]
  (let [name (str "constraint-" (. clojure.lang.RT (nextID)))]
   (Constraint. name m (keys m) (.hashCode name))))

(defn constraint? [x]
  (instance? Constraint x))

(defmethod print-method Constraint [x writer]
  (.write writer (str "<constraint:" (.m ^Constraint x) ">")))

;; =============================================================================
;; Constraint Store

(defprotocol IConstraintStore
  (merge-constraint [this c])
  (refine-constraint [this c u])
  (discard-constraint [this c])
  (propagate [this s u v])
  (get-simplified [this])
  (discard-simplified [this]))

(deftype ConstraintStore [vmap cmap simple]
  IConstraintStore
  (merge-constraint [this c]
                    (let [ks (keys c)]
                      (reduce (fn [cs k] (assoc cs k c)) this ks))) ;; NOTE: switch to loop/recur?

  (refine-constraint [this c u]
                     (let [^Constraint c c
                           name (.name c)
                           c (dissoc (get cmap name) u)
                           vmap (update-in vmap [u] #(disj % name))
                           vmap (if (empty? (vmap u))
                                    (dissoc vmap u)
                                    vmap)]
                       (if (= (count c) 1)
                         (let [okeys (.okeys c)
                               cmap (dissoc cmap name)
                               vmap (reduce (fn [m v]
                                              (update-in m [v] #(disj % name)))
                                            vmap okeys)] ;; NOTE: hmm not all these keys exist
                               ;; TODO: clear out empty vars like below
                           (ConstraintStore. vmap cmap
                                             (conj (or simple [])
                                                   (first c))))
                         (let [cmap (assoc cmap name c)]
                           (ConstraintStore. vmap cmap simple)))))

  (discard-constraint [this c]
                      (let [^Constraint c c
                            name (.name c)
                            c (get cmap name)
                            okeys (.okeys c)
                            cmap (dissoc cmap name)
                            vmap (reduce (fn [m v] ;; TODO: combine
                                           (update-in m [v] #(disj % name)))
                                         vmap okeys)
                            vmap (reduce (fn [m v]
                                           (if (empty? (m v))
                                             (dissoc m v)
                                             m))
                                         vmap okeys)]
                        (ConstraintStore. vmap cmap simple)))

  (propagate [this s u v]
             (if (contains? vmap u)
               (let [cs (get this u)]
                 (loop [[c & cr] cs me this]
                   (if (nil? c)
                     me
                     (let [v' (walk s (get c u))]
                       (cond
                        (= v' v) (recur cr (refine-constraint me c u))
                        (or (lvar? v')
                            (lvar? v)) (recur cr me)
                        :else (recur cr (discard-constraint me c)))))))
               this))

  (get-simplified [this] simple)

  (discard-simplified [this] (ConstraintStore. vmap cmap nil))

  clojure.lang.Associative
  (assoc [this u c]
    (if (constraint? c)
      (let [name (.name ^Constraint c)]
        (ConstraintStore. (update-in vmap [u] (fnil #(conj % name) #{}))
                          (assoc cmap name c) simple))
      (throw (Exception. "Adding something which is not a constraint"))))

  (containsKey [this key]
               (contains? vmap key))

  (entryAt [this key]
           (when (contains? vmap key)
             (let [val (when-let [s (seq (map #(cmap %) (vmap key)))]
                         (vec s))]
               (clojure.core/reify
                clojure.lang.IMapEntry
                (key [_] key)
                (val [_] val)
                Object
                (toString [_] (.toString [key val]))))))

  clojure.lang.ILookup
  (valAt [this key]
         (when (contains? vmap key)
           (when-let [s (seq (map #(cmap %) (vmap key)))]
             (vec s)))))

(defn ^ConstraintStore make-store []
  (ConstraintStore. {} {} nil))

(defn check-vars [s us]
  (when s
   (loop [[u & ur] (seq us)]
     (if (nil? u)
       (use-verify s constraint-verify)
       (let [u (walk-var s u)
             v (walk s u)
             cs (constraints u)]
         (if (and cs (cs v))
           nil
           (recur ur)))))))

;; NOTE: just trying to get this to work when it comes first
;; TODO: refactor to use helper fn
(defmacro all-different [& vars]
  `(fn [a#]
     (let [vars# (set (map #(walk-var a# %) [~@vars]))]
       (check-vars (->> vars#
                        (map (fn [v#]
                               (add-constraints
                                v# (set (map #(walk a# %)
                                             (disj vars# v#))))))
                        (reduce (fn [b# v#] (swap b# v#)) a#))
                   vars#))))

;; =============================================================================
;; Syntax

(defmacro != [u v]
  `(fn [a#]
     (!=-verify a# (unify a# ~u ~v))))


(comment
  ;; all-different
  (run* [q]
        (exist [x y]
               (all-different x y)
               (== x 1)
               (== y 1)
               (== q x)))

  ;; ~120ms, not bad
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e4]
       (doall
        (run* [q]
              (exist [x y]
                     (== x 1)
                     (all-different x y)
                     (== y 1)
                     (== q x)))))))

  ;; 70ms, very close tho, all-different has to do a bit of work
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e4]
       (doall
        (run* [q]
              (exist [x y]
                     (!= x y)
                     (== x 1)
                     (== y 1)
                     (== q x)))))))

  (run* [q]
        (exist [x y]
               (== x 1)
               (all-different x y)
               (== y 1)
               (== q x)))
  
  ;; FIXME
  (run* [q]
        (exist [x y]
               (== x 1)
               (== y 1)
               (all-different x y)
               (== q x)))

  ;; the contraints are there
  ;; this is weird
  (let [[x y] (map lvar '[x y])]
    (-> empty-s
        ((== x 1))
        ((== y 1))
        ((all-different x y))))

  ;; ()
  (run* [q]
        (exist [x y z]
               (!= x y)
               (== y z)
               (== x z)
               (== q x)))

  ;; ([1 3]) works!
  (run* [q]
        (exist [x y]
               (== x 1)
               (!= [x 2] [1 y])
               (== y 3)
               (== q [x y])))

  ;; 500ms
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e4]
       (doall
        (run* [q]
              (exist [x y]
                     (!= [x 2] [1 y])
                     (== x 1)
                     (== y 3)
                     (== q [x y])))))))

  ;; interesting complex constraints are fastest when last
  ;; 68ms
  ;; ~740ms for 1e5
  ;; ~400ms for 1e5 if violated
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e5]
       (doall
        (run* [q]
              (exist [x y]
                     (== x 1)
                     (== y 2)
                     (!= [x 2] [1 y])
                     (== q [x y])))))))

  ;; 271ms
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e4]
       (doall
        (run* [q]
              (exist [x y]
                     (!= x 1)
                     (!= y 2)
                     (== x 1)
                     (== y 2)
                     (== q [x y])))))))

  ;; 60ms
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e4]
       (doall
        (run* [q]
              (exist [x y]
                     (== x 1)
                     (== y 2)
                     (== q [x y])))))))

  ;; 200ms
  (let [[x y z a] (map lvar '(x y z a))
        s (-> empty-s
              (unify x 1)
              (unify y z))
        p1 (pair x 1)
        p2 (pair y 2)
        c [p1 p2]]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e5]
         (unify* s c)))))

  ;; 1.1s, we'll need to track how this affects
  ;; actual logic programs, constraints allow us
  ;; to fail many dead ends quickly.
  (let [[x y z] (map lvar '[x y z])
        cs (make-store)
        c  (make-c {x 1 y 2 z 3})
        cs (merge-constraint cs c)]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e5]
         (-> cs
             (refine-constraint c z)
             (refine-constraint c y))))))

  ;; 500ms
  ;; linear in the number of vars, 5 takes ~850ms
  (let [[x y z a b] (map lvar '[x y z a b])
        cs (make-store)
        c  (make-c {x 1 y 2 z 3})
        cs (merge-constraint cs c)]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e5]
         (discard-constraint cs c)))))

  ;; 350ms
  (let [[x y z] (map lvar '[x y z])
        cs (make-store)
        c  (make-c {x 1 y 2 z 3})
        cs (merge-constraint cs c)]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e5]
         (refine-constraint cs c y)))))

  ;; 150ms
  (let [[x y z] (map lvar '[x y z])
        cs (make-store)
        c  (make-c {x 1 y 2 z 3})]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e5]
         (merge-constraint cs c)))))

  ;; a little bit faster
  (let [[x y z] (map lvar '[x y z])
        cs (make-store)
        c  (make-c {x 1 y 2 z 3})]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e5]
         (-> cs
             (assoc x c)
             (assoc y c)
             (assoc z c))))))
 )

