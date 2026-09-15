import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { expect, within } from "storybook/test";
import { PageInfo } from "./PageInfo";

const meta = {
  title: "Shared/PageInfo",
  component: PageInfo,
  tags: ["autodocs"],
  argTypes: {
    title: { control: "text", description: "페이지 제목" },
    caption: { control: "text", description: "페이지 안내 문구" },
  },
  args: {
    title: "어떤 노선을 이용하시나요?",
    caption: "노선을 선택하면 정류장별 탑승 확률과 도착예상 좌석을 확인할 수 있어요",
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);

    await expect(canvas.getByRole("heading", { level: 1, name: args.title })).toBeVisible();
    await expect(canvas.getByText(args.caption)).toBeVisible();
  },
} satisfies Meta<typeof PageInfo>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Error: Story = {
  args: {
    title: "노선을 불러오지 못했어요",
    caption: "잠시 후 다시 시도해 주세요",
  },
};

export const LongText: Story = {
  args: {
    title: "출발지와 목적지를 확인하고 이용할 버스 노선을 선택해 주세요",
    caption:
      "노선을 선택하면 각 정류장의 탑승 확률과 도착예상 좌석을 확인할 수 있어요. 현재 위치에서 가까운 정류장을 찾아 원하는 시간에 이용할 수 있는 버스를 확인해 보세요.",
  },
};
