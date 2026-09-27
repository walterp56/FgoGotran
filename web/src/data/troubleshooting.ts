export type TroubleshootingGroupDef = {
  id: string;
  items: string[];
};

export const logStepDefs = ["appear", "open-log", "top", "export", "context"];

export const troubleshootingGroupDefs: TroubleshootingGroupDef[] = [
  {
    id: "phone",
    items: ["permission-check", "go-no-translate", "no-voice", "ocr"]
  },
  {
    id: "emulator",
    items: ["which", "go-no-translate", "no-screenshot", "button", "semi-auto", "channel"]
  },
  {
    id: "projection",
    items: ["pc-but-no-translate", "black-screen", "recommend", "brightness"]
  },
  {
    id: "error-meaning",
    items: ["overlay", "accessibility", "no-screenshot", "api", "tts", "package"]
  }
];

export const troubleshootingTocDefs = [
  { href: "#error-log", key: "errorLog" },
  { href: "#phone", key: "phone" },
  { href: "#emulator", key: "emulator" },
  { href: "#projection", key: "projection" },
  { href: "#error-meaning", key: "errorMeaning" }
];