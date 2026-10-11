package com.resq.cache;

import com.resq.domain.ResourceSlot;
import com.resq.mapper.ResourceSlotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceSlotCacheInvalidatorTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    private ResourceSlotMapper mapper;
    private ResourceSlotCache cache;
    private ResourceSlotCacheInvalidator invalidator;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        mapper = mock(ResourceSlotMapper.class);
        cache = mock(ResourceSlotCache.class);
        invalidator = new ResourceSlotCacheInvalidator(mapper, cache);
        when(mapper.findById(10L)).thenReturn(new ResourceSlot(10L, 1L,
                DATE.atTime(9, 0), DATE.atTime(10, 0), "RESERVED"));
        transaction = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition def) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        });
    }

    @Test
    void successfulCommitEvictsOnlyAfterTransactionCompletes() {
        transaction.executeWithoutResult(status -> {
            invalidator.evictAfterCommit(10L);
            verifyNoInteractions(cache);
        });
        verify(cache).evict(1L, DATE);
        verifyNoMoreInteractions(cache);
    }

    @Test
    void rollbackDoesNotEvict() {
        transaction.executeWithoutResult(status -> {
            invalidator.evictAfterCommit(10L);
            status.setRollbackOnly();
        });
        verifyNoInteractions(cache);
    }

    @Test
    void exceptionDoesNotEvict() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            invalidator.evictAfterCommit(10L);
            throw new IllegalStateException("business failed");
        }));
        verifyNoInteractions(cache);
    }

    @Test
    void missingSlotOrLookupFailureDoesNotFailBusinessOrDeleteUnrelatedKeys() {
        when(mapper.findById(10L)).thenReturn(null);
        assertDoesNotThrow(() -> invalidator.evictAfterCommit(10L));
        when(mapper.findById(10L)).thenThrow(new DataAccessResourceFailureException("db unavailable"));
        assertDoesNotThrow(() -> invalidator.evictAfterCommit(10L));
        verifyNoInteractions(cache);
    }
}
