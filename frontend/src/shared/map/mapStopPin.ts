import { stopPin, stopPinGlyph, stopPinLabel } from "./mapStopPin.css";

export function createMapStopPin(name: string, accessibleName: string) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = stopPin;
  button.setAttribute("aria-label", accessibleName);
  const glyph = document.createElement("span");
  glyph.className = stopPinGlyph;
  glyph.setAttribute("aria-hidden", "true");
  button.append(glyph);
  const label = document.createElement("span");
  label.className = stopPinLabel;
  label.textContent = name;
  label.setAttribute("aria-hidden", "true");
  button.append(label);
  return { button, label };
}
