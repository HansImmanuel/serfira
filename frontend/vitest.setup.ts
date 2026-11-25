import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

/**
 * jsdom lacks the pointer-capture and scroll APIs Radix Select calls when it opens, so a test that
 * picks an option would throw inside the library. These no-op stand-ins exist only in the test DOM.
 */
if (typeof Element !== "undefined") {
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.setPointerCapture ??= () => {};
  Element.prototype.releasePointerCapture ??= () => {};
  Element.prototype.scrollIntoView ??= () => {};
}

afterEach(() => {
  cleanup();
});
