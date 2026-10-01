package com.drnoob.datamonitor;

import com.drnoob.datamonitor.utils.TransportStatsRepository;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class TransportStatsRepositoryTest {
    @Test
    public void scaleProgress_clamps() {
        assertEquals(0f, TransportStatsRepository.scaleProgress(0f, 500f), 0.001f);
        assertEquals(0f, TransportStatsRepository.scaleProgress(-5f, 500f), 0.001f);
        assertEquals(0f, TransportStatsRepository.scaleProgress(10f, 0f), 0.001f);
        assertEquals(0.5f, TransportStatsRepository.scaleProgress(250f, 500f), 0.001f);
        assertEquals(1f, TransportStatsRepository.scaleProgress(600f, 500f), 0.001f);
        assertEquals(1f, TransportStatsRepository.scaleProgress(3500f, 500f), 0.001f);
    }

    @Test
    public void reconcile_prefersMax() {
        assertEquals(100L, TransportStatsRepository.reconcile(100L, 60L));
        assertEquals(120L, TransportStatsRepository.reconcile(100L, 120L));
        assertEquals(0L, TransportStatsRepository.reconcile(-5L, -10L));
    }

    @Test
    public void splitRange_chunks2h() {
        List<long[]> chunks = TransportStatsRepository.splitRange(0L, 7L * 24L * 3600L * 1000L,
                TransportStatsRepository.CHUNK_MILLIS);
        assertEquals(84, chunks.size());
        assertEquals(0L, chunks.get(0)[0]);
    }

    @Test
    public void filter_allSumsMobileWifi() {
        TransportStatsRepository.DayUsage d = new TransportStatsRepository.DayUsage(100L, 200L, null);
        assertEquals(300L, TransportStatsRepository.getTotalForFilter(d, null, null));
        assertEquals(100L, TransportStatsRepository.getTotalForFilter(d,
                TransportStatsRepository.Transport.MOBILE, null));
        assertEquals(200L, TransportStatsRepository.getTotalForFilter(d,
                TransportStatsRepository.Transport.WIFI, null));
    }
}
