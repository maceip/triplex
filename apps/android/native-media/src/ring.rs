use core::cell::UnsafeCell;
use core::mem::MaybeUninit;
use core::sync::atomic::{AtomicU32, Ordering};

#[repr(align(64))]
struct CachePadded<T>(T);

/// Fixed-capacity wait-free SPSC ring.
///
/// The caller must uphold the single-producer/single-consumer contract. The
/// ring allocates its backing storage once in `new`; push/pop never allocate,
/// lock, park, log, or perform I/O.
pub struct SpscRing<T: Copy, const N: usize> {
    head: CachePadded<AtomicU32>,
    tail: CachePadded<AtomicU32>,
    slots: Box<[UnsafeCell<MaybeUninit<T>>; N]>,
}

// Safety: producer and consumer access disjoint slots. A Release publication
// of head and Acquire observation of head transfer ownership to the consumer;
// tail performs the inverse transfer back to the producer.
unsafe impl<T: Copy + Send, const N: usize> Sync for SpscRing<T, N> {}
unsafe impl<T: Copy + Send, const N: usize> Send for SpscRing<T, N> {}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Full;

impl<T: Copy, const N: usize> SpscRing<T, N> {
    pub fn new() -> Self {
        assert!(N > 0 && N.is_power_of_two());
        Self {
            head: CachePadded(AtomicU32::new(0)),
            tail: CachePadded(AtomicU32::new(0)),
            slots: Box::new(core::array::from_fn(|_| {
                UnsafeCell::new(MaybeUninit::uninit())
            })),
        }
    }

    #[inline]
    pub fn len(&self) -> usize {
        let head = self.head.0.load(Ordering::Acquire);
        let tail = self.tail.0.load(Ordering::Acquire);
        head.wrapping_sub(tail) as usize
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    #[inline]
    pub fn is_full(&self) -> bool {
        self.len() == N
    }

    #[inline]
    pub fn push(&self, value: T) -> Result<(), Full> {
        let head = self.head.0.load(Ordering::Relaxed);
        let tail = self.tail.0.load(Ordering::Acquire);
        if head.wrapping_sub(tail) as usize == N {
            return Err(Full);
        }

        let index = head as usize & (N - 1);
        // Safety: only the producer writes the slot at `head`, and it cannot
        // be visible to the consumer until the Release store below.
        unsafe { (*self.slots[index].get()).write(value) };
        self.head.0.store(head.wrapping_add(1), Ordering::Release);
        Ok(())
    }

    #[inline]
    pub fn pop(&self) -> Option<T> {
        let tail = self.tail.0.load(Ordering::Relaxed);
        let head = self.head.0.load(Ordering::Acquire);
        if tail == head {
            return None;
        }

        let index = tail as usize & (N - 1);
        // Safety: the Acquire load above observes the producer's initialized
        // slot, and only the consumer reads this slot before publishing tail.
        let value = unsafe { (*self.slots[index].get()).assume_init_read() };
        self.tail.0.store(tail.wrapping_add(1), Ordering::Release);
        Some(value)
    }
}

impl<T: Copy, const N: usize> Default for SpscRing<T, N> {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn rejects_overflow_and_preserves_fifo_order() {
        let ring = SpscRing::<u32, 4>::new();
        for value in 0..4 {
            assert_eq!(ring.push(value), Ok(()));
        }
        assert_eq!(ring.push(4), Err(Full));
        for value in 0..4 {
            assert_eq!(ring.pop(), Some(value));
        }
        assert_eq!(ring.pop(), None);
    }

    #[test]
    fn transfers_ownership_between_threads() {
        const ITEMS: u32 = 100_000;
        let ring = Arc::new(SpscRing::<u32, 1024>::new());
        let producer_ring = Arc::clone(&ring);
        let producer = thread::spawn(move || {
            for value in 0..ITEMS {
                while producer_ring.push(value).is_err() {
                    core::hint::spin_loop();
                }
            }
        });

        for expected in 0..ITEMS {
            loop {
                if let Some(actual) = ring.pop() {
                    assert_eq!(actual, expected);
                    break;
                }
                core::hint::spin_loop();
            }
        }
        producer.join().unwrap();
    }
}
