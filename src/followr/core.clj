(ns followr.core
  (:gen-class)
  (:require [clj-time.core :as t :refer [days ago]]
            [clj-time.coerce :as tc]
            [clojure.tools.logging :as log]
            [followr.db :as db]
            [followr.config :refer [config]]
            [followr.flickr :as flickr]
            [clojure.java.jdbc :as jdbc]
            [honeysql.core :as sql]))

(defn- current-following [db]
  (map :user_id (jdbc/query db (sql/format {:select [:user_id]
                                            :from [:following]}))))

(defn- following-since [db cutoff]
  (map :user_id (jdbc/query db (sql/format {:select [:user_id]
                                            :from [:following]
                                            :where [:and
                                                    [:= :currently_following true]
                                                    [:< :followed_on (tc/to-sql-time cutoff)]]}))))

(defn mark-followed! [db user-id]
  (jdbc/with-db-transaction [db db]
    (jdbc/insert! db :following {:user_id user-id
                                 :currently_following true})))

(defn mark-unfollowed! [db user-id]
  (jdbc/with-db-transaction [db db]
    (jdbc/update! db :following
                  {:currently_following false}
                  ["user_id = ?" user-id])))

(defn- find-candidates [currently-following]
  (->> (flickr/random-group-members (rand-nth ["38436807@N00" ;;flickr today
                                               "34427469792@N01" ;; flickr central
                                               ]))
       (remove currently-following)
       (shuffle)
       (filter
        #(let [{:keys [user-id photo-count last-uploaded] :as candidate} (flickr/user-photos-summary %)]
           (log/info "Considering candidate" candidate)
           (and (< 100 photo-count)
                last-uploaded
                (t/after? last-uploaded (-> 120 days ago)))))))

(defn- followr []
  (let [{:keys [db-url]} (config)
        db (db/create-db-connection db-url)
        currently-following (set (current-following db))
        candidates (take 5 (find-candidates currently-following))]

    (log/info "Currently following" (count currently-following))
    (log/info "Found" (count candidates) "new candidates")

    (doseq [candidate candidates]
      (do (log/info "Following" candidate)
          (mark-followed! db candidate)
          (flickr/add-contact! candidate)))

    (doseq [user (following-since db (-> 14 days ago))]
      (log/info "Removing old user" user)
      (mark-unfollowed! db user)
      (flickr/remove-contact! user))))

(defn -main [& args]
  (followr)
  (System/exit 0))
