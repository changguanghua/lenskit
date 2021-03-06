/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2013 Regents of the University of Minnesota and contributors
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.knn.user;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.longs.*;
import org.grouplens.lenskit.data.event.Event;
import org.grouplens.lenskit.data.history.UserHistory;
import org.grouplens.lenskit.data.dao.ItemEventDAO;
import org.grouplens.lenskit.data.dao.UserEventDAO;
import org.grouplens.lenskit.data.event.Rating;
import org.grouplens.lenskit.data.event.Ratings;
import org.grouplens.lenskit.data.history.RatingVectorUserHistorySummarizer;
import org.grouplens.lenskit.knn.NeighborhoodSize;
import org.grouplens.lenskit.transform.normalize.UserVectorNormalizer;
import org.grouplens.lenskit.vectors.MutableSparseVector;
import org.grouplens.lenskit.vectors.SparseVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;

import static java.lang.Math.max;

/**
 * Neighborhood finder that does a fresh search over the data source ever time.
 *
 * <p>This rating vector has support for caching user rating vectors, where it
 * avoids rebuilding user rating vectors for users with no changed user. When
 * caching is enabled, it assumes that the underlying data is timestamped and
 * that the timestamps are well-behaved: if a rating has been added after the
 * currently cached rating vector was computed, then its timestamp is greater
 * than any timestamp seen while computing the cached vector.
 *
 * <p>Currently, this cache is never cleared. This should probably be changed
 * sometime.
 *
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 */
public class SimpleNeighborhoodFinder implements NeighborhoodFinder, Serializable {
    private static final long serialVersionUID = -6324767320394518347L;
    private static final Logger logger = LoggerFactory.getLogger(SimpleNeighborhoodFinder.class);

    static class CacheEntry {
        final long user;
        final SparseVector ratings;
        final long lastRatingTimestamp;
        final int ratingCount;

        CacheEntry(long uid, SparseVector urv, long ts, int count) {
            user = uid;
            ratings = urv;
            lastRatingTimestamp = ts;
            ratingCount = count;
        }
    }

    private final UserEventDAO userDAO;
    private final ItemEventDAO itemDAO;
    private final int neighborhoodSize;
    private final UserSimilarity similarity;
    private final UserVectorNormalizer normalizer;
    private final Long2ObjectMap<CacheEntry> userVectorCache;

    /**
     * Construct a new user-user recommender.
     *
     * @param udao  The user-event DAO.
     * @param idao  The item-event DAO.
     * @param nnbrs The number of neighbors to consider for each item.
     * @param sim   The similarity function to use.
     */
    @Inject
    public SimpleNeighborhoodFinder(UserEventDAO udao, ItemEventDAO idao,
                                    @NeighborhoodSize int nnbrs,
                                    UserSimilarity sim,
                                    UserVectorNormalizer norm) {
        userDAO = udao;
        itemDAO = idao;
        neighborhoodSize = nnbrs;
        similarity = sim;
        normalizer = norm;
        userVectorCache = new Long2ObjectOpenHashMap<CacheEntry>(500);
    }

    /**
     * Find the neighbors for a user with respect to a collection of items.
     * For each item, the {@var neighborhoodSize} users closest to the
     * provided user are returned.
     *
     * @param user  The user's rating vector.
     * @param items The items for which neighborhoods are requested.
     * @return A mapping of item IDs to neighborhoods.
     */
    @Override
    public Long2ObjectMap<? extends Collection<Neighbor>>
    findNeighbors(@Nonnull UserHistory<? extends Event> user, @Nonnull LongSet items) {
        Preconditions.checkNotNull(user, "user profile");
        Preconditions.checkNotNull(user, "item set");

        Long2ObjectMap<PriorityQueue<Neighbor>> heaps =
                new Long2ObjectOpenHashMap<PriorityQueue<Neighbor>>(items != null ? items.size() : 100);

        SparseVector urs = RatingVectorUserHistorySummarizer.makeRatingVector(user);
        final long uid1 = user.getUserId();
        MutableSparseVector nratings = normalizer.normalize(user.getUserId(), urs, null);

        /* Find candidate neighbors. To reduce scanning, we limit users to those
         * rating target items. If the similarity is sparse and the user has
         * fewer items than target items, then we use the user's rated items to
         * attempt to minimize the number of users considered.
         */
        LongSet users = findRatingUsers(user.getUserId(), items);

        logger.trace("Found {} candidate neighbors", users.size());

        LongIterator uiter = users.iterator();
        while (uiter.hasNext()) {
            final long uid2 = uiter.nextLong();
            SparseVector urv = getUserRatingVector(uid2);
            MutableSparseVector nurv = normalizer.normalize(uid2, urv, null);

            final double sim = similarity.similarity(uid1, nratings, uid2, nurv);
            if (Double.isNaN(sim) || Double.isInfinite(sim)) {
                continue;
            }
            final Neighbor n = new Neighbor(uid2, urv, sim);

            LongIterator iit = urv.keySet().iterator();
            while (iit.hasNext()) {
                final long item = iit.nextLong();
                if (items.contains(item)) {
                    PriorityQueue<Neighbor> heap = heaps.get(item);
                    if (heap == null) {
                        heap = new PriorityQueue<Neighbor>(neighborhoodSize + 1,
                                                           Neighbor.SIMILARITY_COMPARATOR);
                        heaps.put(item, heap);
                    }
                    heap.add(n);
                    if (heap.size() > neighborhoodSize) {
                        assert heap.size() == neighborhoodSize + 1;
                        heap.remove();
                    }
                }
            }
        }
        return heaps;
    }

    /**
     * Find all users who have rated any of a set of items.
     *
     * @param user    The current user's ID (excluded from the returned set).
     * @param itemSet The set of items to look for.
     * @return The set of all users who have rated at least one item in {@var itemSet}.
     */
    private LongSet findRatingUsers(long user, LongCollection itemSet) {
        LongSet users = new LongOpenHashSet(100);

        LongIterator items = itemSet.iterator();
        while (items.hasNext()) {
            LongSet iusers = itemDAO.getUsersForItem(items.nextLong());
            if (iusers != null) {
                users.addAll(iusers);
            }
        }
        users.remove(user);

        return users;
    }

    /**
     * Look up the user's rating vector, using the cached version if possible.
     *
     * @param user The user ID.
     * @return The user's rating vector.
     */
    private synchronized SparseVector getUserRatingVector(long user) {
        List<Rating> ratings = userDAO.getEventsForUser(user, Rating.class);
        CacheEntry e = userVectorCache.get(user);

        // check rating count
        if (e != null && e.ratingCount != ratings.size()) {
            e = null;
        }

        // check max timestamp
        long ts = -1;
        if (e != null) {
            for (Rating r : ratings) {
                ts = max(ts, r.getTimestamp());
            }
            if (ts != e.lastRatingTimestamp) {
                e = null;
            }
        }

        // create new cache entry
        if (e == null) {
            SparseVector v = Ratings.userRatingVector(ratings);
            e = new CacheEntry(user, v, ts, ratings.size());
            userVectorCache.put(user, e);
        }

        return e.ratings;
    }
}
