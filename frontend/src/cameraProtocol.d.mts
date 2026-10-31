export class FrameBudget {
  constructor(startedAt: number, options?: { maxFrames?: number; maxBytes?: number; maxFrameBytes?: number; durationMs?: number });
  frames: number; bytes: number;
  accept(size: number, now: number): boolean;
}
export function maskedIdentity(name: unknown, employeeNo: unknown): { employeeName: string; employeeNo: string };
export function captureRect(width: number, height: number, targetWidth?: number, targetHeight?: number): { x: number; y: number; width: number; height: number } | null;
