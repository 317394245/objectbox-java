package io.objectbox;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import io.objectbox.reactive.Observer;
import io.objectbox.reactive.RunWithParam;
import io.objectbox.reactive.Scheduler;
import io.objectbox.reactive.Subscription;
import io.objectbox.reactive.SubscriptionBuilder;
import io.objectbox.reactive.Transformer;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ObjectClassObserverTest extends AbstractObjectBoxTest {

    protected BoxStore createBoxStore() {
        return createBoxStoreBuilderWithTwoEntities(false).build();
    }

    final List<Class> classesWithChanges = new ArrayList<>();
    Observer objectClassObserver = new Observer<Class>() {
        @Override
        public void onChange(Class objectClass) {
            classesWithChanges.add(objectClass);
        }
    };

    Runnable txRunnable = new Runnable() {
        @Override
        public void run() {
            putTestEntities(3);
            Box<TestEntityMinimal> boxMini = store.boxFor(TestEntityMinimal.class);
            boxMini.put(new TestEntityMinimal(), new TestEntityMinimal());
            assertEquals(0, classesWithChanges.size());
        }
    };

    @Before
    public void clear() {
        classesWithChanges.clear();
    }

    @Test
    public void testTwoObjectClassesChanged_catchAllListener() {
        testTwoObjectClassesChanged_catchAllListener(false);
    }

    @Test
    public void testTwoObjectClassesChanged_catchAllListenerWeak() {
        testTwoObjectClassesChanged_catchAllListener(true);
    }

    public void testTwoObjectClassesChanged_catchAllListener(boolean weak) {
        Subscription subscription = subscribe(weak, null);
        store.runInTx(new Runnable() {
            @Override
            public void run() {
                // Dummy TX, still will be committed
                getTestEntityBox().count();
            }
        });
        assertEquals(0, classesWithChanges.size());

        store.runInTx(txRunnable);
        assertEquals(2, classesWithChanges.size());
        assertTrue(classesWithChanges.contains(TestEntity.class));
        assertTrue(classesWithChanges.contains(TestEntityMinimal.class));

        classesWithChanges.clear();
        subscription.cancel();
        store.runInTx(txRunnable);
        assertEquals(0, classesWithChanges.size());
    }

    private Subscription subscribe(boolean weak, Class forClass) {
        SubscriptionBuilder<Class> subscriptionBuilder = store.subscribe(forClass);
        return (weak ? subscriptionBuilder.weak() : subscriptionBuilder).subscribe(objectClassObserver);
    }

    @Test
    public void testTwoObjectClassesChanged_oneClassObserver() {
        testTwoObjectClassesChanged_oneClassObserver(false);
    }

    @Test
    public void testTwoObjectClassesChanged_oneClassObserverWeak() {
        testTwoObjectClassesChanged_oneClassObserver(true);
    }

    public void testTwoObjectClassesChanged_oneClassObserver(boolean weak) {
        Subscription subscription = subscribe(weak, TestEntityMinimal.class);

        store.runInTx(txRunnable);

        assertEquals(1, classesWithChanges.size());
        assertEquals(classesWithChanges.get(0), TestEntityMinimal.class);

        classesWithChanges.clear();
        putTestEntities(1);
        assertEquals(0, classesWithChanges.size());

        // Adding twice should not trigger notification twice
        Subscription subscription2 = subscribe(weak, TestEntityMinimal.class);

        Box<TestEntityMinimal> boxMini = store.boxFor(TestEntityMinimal.class);
        boxMini.put(new TestEntityMinimal(), new TestEntityMinimal());
        assertEquals(1, classesWithChanges.size());

        classesWithChanges.clear();
        subscription.cancel();
        store.runInTx(txRunnable);
        assertEquals(0, classesWithChanges.size());
    }

    @Test
    public void testTransform() throws InterruptedException {
        final List<Long> objectCounts = new ArrayList<>();
        final CountDownLatch latch= new CountDownLatch(2);
        final Thread testThread = Thread.currentThread();
        Subscription subscription = store.subscribe().transform(new Transformer<Class, Long>() {
            @Override
            public Long transform(Class source) throws Exception {
                assertNotSame(testThread, Thread.currentThread());
                return store.boxFor(source).count();
            }
        }).subscribe(new Observer<Long>() {
            @Override
            public void onChange(Long data) {
                objectCounts.add(data);
                latch.countDown();
            }
        });

        store.runInTx(new Runnable() {
            @Override
            public void run() {
                // Dummy TX, still will be committed, should not add anything to objectCounts
                getTestEntityBox().count();
            }
        });

        store.runInTx(txRunnable);

        assertLatchCountedDown(latch, 5);
        assertEquals(2, objectCounts.size());
        assertTrue(objectCounts.contains(2L));
        assertTrue(objectCounts.contains(3L));

        objectCounts.clear();
        subscription.cancel();
        store.runInTx(txRunnable);
        Thread.sleep(20);
        assertEquals(0, objectCounts.size());
    }

    @Test
    public void testScheduler() throws InterruptedException {
        final int[] schedulerCounter= {0};
        Subscription subscription = store.subscribe().on(new Scheduler() {
            @Override
            public <T> void run(RunWithParam runnable, T param) {
                schedulerCounter[0]++;
                runnable.run(param);
            }
        }).subscribe(objectClassObserver);

        store.runInTx(txRunnable);

        assertEquals(2, schedulerCounter[0]);
        assertEquals(2, classesWithChanges.size());
    }

}