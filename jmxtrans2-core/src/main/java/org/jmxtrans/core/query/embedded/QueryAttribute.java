/**
 * The MIT License
 * Copyright (c) 2014 JMXTrans Team
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jmxtrans.core.query.embedded;

import org.jmxtrans.log.Logger;
import org.jmxtrans.log.LoggerFactory;
import org.jmxtrans.core.results.QueryResult;
import org.jmxtrans.utils.Preconditions2;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.hash;

/**
 * Describe a JMX MBean attribute to collect and hold the attribute collection logic.
 * <p/>
 * Collected values are sent to a {@linkplain java.util.concurrent.BlockingQueue}
 * for later export to the target monitoring systems
 * (see {@link #collectMetrics(javax.management.ObjectName, Object, long, java.util.Collection, Query, ResultNameStrategy)}.
 *
 * @author <a href="mailto:cleclerc@xebia.fr">Cyrille Le Clerc</a>
 * @author Jon Stevens
 */
public class QueryAttribute {

    @Nonnull
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());

    /**
     * Name of the JMX Attribute to collect
     */
    @Nonnull
    private final String name;
    /**
     * Used to create the name of the {@link org.jmxtrans.core.results.QueryResult} that will be exported.
     * <p/>
     * <code>null</code> if not defined in the configuration. The {@link #name} is then used to create to {@linkplain org.jmxtrans.core.results.QueryResult}
     *
     * @see org.jmxtrans.core.results.QueryResult#getName()
     */
    @Nullable
    private final String resultAlias;

    /**
     * Attribute type like '{@code gauge}' or '{@code counter}'. Used by monitoring systems like Librato who require this information.
     *
     * @see org.jmxtrans.core.results.QueryResult#getName()
     */
    @Nullable
    private final String type;

    /**
     * <code>null</code> if no 'key' as been defined in the config.
     * Empty list if empty 'key' node has been declared in the config.
     *
     * @see javax.management.openmbean.CompositeType#keySet()
     */
    @Nullable
    private final Set<String> keys;

    /**
     * @param name        name of the JMX attribute
     * @param type        type of the metric (e.g. "{@code counter}", "{@code gauge}", ...)
     * @param resultAlias name of the result that will be exported
     * @param keys        of the {@link javax.management.openmbean.CompositeData} to collect
     */
    private QueryAttribute(@Nonnull String name, @Nullable String type, @Nullable String resultAlias, @Nullable Set<String> keys) {
        this.name = Preconditions2.checkNotEmpty(name);
        this.type = type;
        this.resultAlias = resultAlias;
        this.keys = keys;
    }

    @Nonnull
    public String getName() {
        return name;
    }

    @Nullable
    public String getResultAlias() {
        return resultAlias;
    }

    @Nullable
    public String getType() {
        return type;
    }

    /**
     * @param objectName    <code>objectName</code> on which the <code>attribute</code> was obtained.
     * @param value         value of the given attribute. A 'simple' value (String, Number, Date)
     *                      or a {@link javax.management.openmbean.CompositeData}
     * @param epochInMillis time at which the metric was collected
     * @param results       queue to which the the computed result(s) must be added
     * @param query
     * @param resultNameStrategy
     * @return collected metrics count
     */
    public int collectMetrics(
            @Nonnull ObjectName objectName,
            @Nonnull Object value,
            long epochInMillis,
            @Nonnull Collection<QueryResult> results,
            @Nonnull Query query,
            @Nonnull ResultNameStrategy resultNameStrategy) {
        if (value instanceof CompositeData) {
            CompositeData compositeData = (CompositeData) value;
            return collectCompositeData(objectName, epochInMillis, results, query, resultNameStrategy, compositeData);
        } else if (value instanceof Number || value instanceof String || value instanceof Date) {
            return collectScalar(objectName, value, epochInMillis, results, query, resultNameStrategy);
        }
        logger.info(format("Ignore non CompositeData attribute value %s:%s:%s=%s", query, objectName, this, value));
        return 0;
    }

    private int collectScalar(
            @Nonnull ObjectName objectName,
            @Nullable Object value,
            long epochInMillis,
            @Nonnull Collection<QueryResult> results,
            @Nonnull Query query,
            @Nonnull ResultNameStrategy resultNameStrategy) {
        if (keys != null && logger.isInfoEnabled()) {
            logger.info(format("Ignore keys configured for 'simple' jmx attribute. %s:%s:%s", query, objectName, this));
        }
        String resultName = resultNameStrategy.getResultName(query, objectName, this);
        QueryResult result = new QueryResult(resultName, getType(), value, epochInMillis);
        logger.debug("Collect " + result);
        results.add(result);
        return 1;
    }

    private int collectCompositeData(
            @Nonnull ObjectName objectName,
            long epochInMillis,
            @Nonnull Collection<QueryResult> results,
            @Nonnull Query query,
            @Nonnull ResultNameStrategy resultNameStrategy,
            @Nonnull CompositeData compositeData) {
        int metricsCounter = 0;
        String[] keysToCollect;
        if (keys == null) {
            keysToCollect = compositeData.getCompositeType().keySet().toArray(new String[0]);
            logger.info(format("No 'key' has been configured to collect data on this Composite attribute, collect all keys. %s:%s:%s", query, objectName, this));
        } else {
            keysToCollect = keys.toArray(new String[keys.size()]);
        }
        for (String key : keysToCollect) {
            String resultName = resultNameStrategy.getResultName(query, objectName, this, key);
            Object compositeValue = compositeData.get(key);
            if (compositeValue instanceof Number || compositeValue instanceof String || compositeValue instanceof Date) {
                QueryResult result = new QueryResult(resultName, getType(), compositeValue, epochInMillis);
                logger.debug("Collect " + result);
                results.add(result);
                metricsCounter++;
            } else {
                logger.debug(format("Skip non supported value %s:%s:%s:%s=%s", query, objectName, this, key, compositeValue));
            }
        }
        return metricsCounter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        QueryAttribute that = (QueryAttribute) o;

        return Objects.equals(keys, that.keys)
                && Objects.equals(name, that.name)
                && Objects.equals(resultAlias, that.resultAlias)
                && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return hash(name, resultAlias, type, keys);
    }

    @Override
    @Nonnull
    public String toString() {
        return "QueryAttribute{" +
                "name='" + getName() + '\'' +
                ", resultAlias='" + getResultAlias() + '\'' +
                ", keys=" + (keys == null ? null : Arrays.asList(keys)) +
                '}';
    }

    @Nonnull
    public static Builder builder(@Nonnull String name) {
        return new Builder(name);
    }

    public static final class Builder {
        @Nonnull
        private String name;
        @Nullable
        private String type;
        @Nullable
        private String resultAlias;
        @Nullable
        private Set<String> keys;

        private Builder(@Nonnull String name) {
            this.name = name;
        }

        public Builder withResultAlias(@Nullable String resultAlias) {
            this.resultAlias = resultAlias;
            return this;
        }

        public Builder withType(@Nullable String type) {
            this.type = type;
            return this;
        }

        public void addKey(String key) {
            if (keys == null) keys = new HashSet<>();
            keys.add(key);
        }

        public Builder withKeys(@Nullable Collection<String> keys) {
            if (keys != null) {
                this.keys = new HashSet<>();
                this.keys.addAll(keys);
            } else {
                this.keys = null;
            }
            return this;
        }

        @Nonnull
        public QueryAttribute build() {
            return new QueryAttribute(name, type, resultAlias, keys);
        }
    }
}
