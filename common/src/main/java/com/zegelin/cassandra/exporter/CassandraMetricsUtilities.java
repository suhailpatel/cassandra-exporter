package com.zegelin.cassandra.exporter;

import com.codahale.metrics.Counting;
import com.codahale.metrics.Metric;
import com.codahale.metrics.Sampling;
import com.codahale.metrics.Snapshot;
import com.zegelin.jmx.NamedObject;
import com.zegelin.prometheus.domain.Interval;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxHistogramMBean;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxTimerMBean;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

@SuppressWarnings("Duplicates")
public class CassandraMetricsUtilities {
    private static final Map<Class<? extends CassandraMetricsRegistry.MetricMBean>, Field> MBEAN_METRIC_FIELDS = new HashMap<>();

    /**
     * Given a Cassandra MetricMBean return the internal Codahale/Dropwizard Metric object
     */
    public static <T> NamedObject<T> metricForMBean(final NamedObject<?> mBean) {
        return mBean.map((name, object) -> {
            if (!(object instanceof CassandraMetricsRegistry.MetricMBean)) {
                throw new ClassCastException(String.format("MBean %s isn't an instance of CassandraMetricsRegistry.MetricMBean", name));
            }

            final CassandraMetricsRegistry.MetricMBean metricMBean = (CassandraMetricsRegistry.MetricMBean) object;

            try {
                final Field metricField = MBEAN_METRIC_FIELDS.computeIfAbsent(metricMBean.getClass(), clazz -> {
                    try {
                        @SuppressWarnings("JavaReflectionMemberAccess")
                        final Field field = clazz.getDeclaredField("metric");
                        field.setAccessible(true);

                        return field;

                    } catch (final NoSuchFieldException e) {
                        throw new IllegalArgumentException(e);
                    }
                });

                final Metric rawMetric = (Metric) metricField.get(object);

                return (T) rawMetric;

            } catch (final IllegalAccessException e) {
                throw new IllegalArgumentException(e);
            }
        });
    }

    static SamplingCounting adaptTimer(final JmxTimerMBean timer) {
        return new SamplingCounting() {
            @Override
            public long getCount() {
                return timer.getCount();
            }

            @Override
            public Stream<Interval> getIntervals() {
                return Stream.of(
                        new Interval(Interval.Quantile.P_50, (float) timer.get50thPercentile()),
                        new Interval(Interval.Quantile.P_75, (float) timer.get75thPercentile()),
                        new Interval(Interval.Quantile.P_95, (float) timer.get95thPercentile()),
                        new Interval(Interval.Quantile.P_98, (float) timer.get98thPercentile()),
                        new Interval(Interval.Quantile.P_99, (float) timer.get99thPercentile()),
                        new Interval(Interval.Quantile.P_99_9, (float) timer.get999thPercentile())
                );
            }
        };
    }

    static SamplingCounting adaptHistogram(final JmxHistogramMBean histogram) {
        return new SamplingCounting() {
            @Override
            public long getCount() {
                return histogram.getCount();
            }

            @Override
            public Stream<Interval> getIntervals() {
                return Stream.of(
                        new Interval(Interval.Quantile.P_50, (float) histogram.get50thPercentile()),
                        new Interval(Interval.Quantile.P_75, (float) histogram.get75thPercentile()),
                        new Interval(Interval.Quantile.P_95, (float) histogram.get95thPercentile()),
                        new Interval(Interval.Quantile.P_98, (float) histogram.get98thPercentile()),
                        new Interval(Interval.Quantile.P_99, (float) histogram.get99thPercentile()),
                        new Interval(Interval.Quantile.P_99_9, (float) histogram.get999thPercentile())
                );
            }
        };
    }


    static <X extends Sampling & Counting> SamplingCounting adapt(final X metric) {
        return new SamplingCounting() {
            @Override
            public long getCount() {
                return metric.getCount();
            }

            @Override
            public Stream<Interval> getIntervals() {
                final Snapshot snapshot = metric.getSnapshot();

                return Interval.asIntervals(Interval.Quantile.STANDARD_PERCENTILES, q -> (float) snapshot.getValue(q.value));
            }
        };
    }

    /**
     * Timers and Histograms can be optimised if access to the "raw" {@link com.codahale.metrics.Metric} is available (in-process only).
     * This function tries to access the given {@link NamedObject}'s raw Metric, and adapt it to a {@link SamplingCounting}, failing back to adapting
     * the JMX proxy object to a {@link SamplingCounting}.
     */
    private static <RawT extends Sampling & Counting, MBeanT> NamedObject<SamplingCounting> mBeanAsSamplingCounting(final NamedObject<?> mBean, final Function<MBeanT, SamplingCounting> mBeanAdapterFunction) {
        try {
            return CassandraMetricsUtilities.<RawT>metricForMBean(mBean).map((n, o) -> adapt(o));

        } catch (final Exception e) {
            return mBean.<MBeanT>cast().map((n, o) -> mBeanAdapterFunction.apply(o));
        }
    }

    public static NamedObject<SamplingCounting> jmxTimerMBeanAsSamplingCounting(final NamedObject<?> timerMBean) {
        return mBeanAsSamplingCounting(timerMBean, CassandraMetricsUtilities::adaptTimer);
    }

    public static NamedObject<SamplingCounting> jmxHistogramAsSamplingCounting(final NamedObject<?> histogramMBean) {
        return mBeanAsSamplingCounting(histogramMBean, CassandraMetricsUtilities::adaptHistogram);
    }
}
