import { Image, Pressable, StyleSheet, Text, View } from 'react-native';

const color = {
  surface: '#FFFFFF',
  textStrong: '#151515',
  textSubtle: '#6B6A6A',
  ink: '#111111',
  onInk: '#FFFFFF',
};

interface LoadFailedViewProps {
  onRetry: () => void;
}

export function LoadFailedView({ onRetry }: LoadFailedViewProps) {
  return (
    <View style={styles.container}>
      <Image source={require('./assets/salmong-crying.png')} style={styles.mascot} />
      <Text style={styles.title}>화면을 불러오지 못했어요</Text>
      <Text style={styles.caption}>잠시 후 다시 시도해 주세요</Text>
      <Pressable
        accessibilityRole="button"
        onPress={onRetry}
        style={({ pressed }) => [styles.retryButton, pressed && styles.retryButtonPressed]}
      >
        <Text style={styles.retryLabel}>다시 시도하기</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
    backgroundColor: color.surface,
  },
  mascot: {
    width: 96,
    height: 96,
    marginBottom: 20,
  },
  title: {
    color: color.textStrong,
    fontSize: 20,
    fontWeight: '700',
  },
  caption: {
    marginTop: 8,
    color: color.textSubtle,
    fontSize: 15,
  },
  retryButton: {
    marginTop: 28,
    paddingHorizontal: 24,
    paddingVertical: 14,
    borderRadius: 14,
    backgroundColor: color.ink,
  },
  retryButtonPressed: {
    transform: [{ scale: 0.97 }],
  },
  retryLabel: {
    color: color.onInk,
    fontSize: 16,
    fontWeight: '600',
  },
});
