import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { expect, fn, within } from "storybook/test";
import { ErrorView } from "./ErrorView";

const meta = {
  title: "Shared/ErrorView",
  component: ErrorView,
  tags: ["autodocs"],
  args: {
    title: "노선을 불러오지 못했어요",
    caption: "잠시 후 다시 시도해 주세요",
  },
  argTypes: {
    title: { control: "text", description: "페이지 제목" },
    caption: { control: "text", description: "페이지 안내 문구" },
    onBack: { control: false, description: "뒤로가기 버튼" },
    onRetry: { control: false, description: "다시 시도하기 버튼" },
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);

    await expect(canvas.getByRole("heading", { level: 1, name: args.title })).toBeVisible();
    await expect(canvas.getByText(args.caption)).toBeVisible();
  },
} satisfies Meta<typeof ErrorView>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: {
    onBack: fn(),
    onRetry: fn(),
  },
};

export const WithoutBackButton: Story = {
  args: {
    onRetry: fn(),
  },
};
export const WithoutRetryButton: Story = {
  args: {
    onBack: fn(),
  },
};
