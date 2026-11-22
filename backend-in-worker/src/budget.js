/**
 * 子请求预算：免费版一次调用最多 50 个 fetch（D1 查询也计入）。
 * 解析链路每次真正发请求前从这里扣，扣不动就抛 BudgetExhausted，
 * 由调度器把任务放回队列，下一轮接着跑 —— 而不是把调用打到 1102（超出 CPU/子请求）。
 */
export class BudgetExhausted extends Error {
  constructor() {
    super('本次调用的子请求预算已用尽');
    this.budgetExhausted = true;
  }
}

export function createBudget(left) {
  return {
    left,
    take(n = 1) {
      if (this.left < n) throw new BudgetExhausted();
      this.left -= n;
    },
    /** 探检查缓存、或本该失败也不该因此重试的调用用这个：扣不动就返回 false */
    tryTake(n = 1) {
      if (this.left < n) return false;
      this.left -= n;
      return true;
    },
  };
}