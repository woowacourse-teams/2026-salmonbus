# 별도 구현을 두지 않고 CodeBuild와 같은 런타임 입력 목록·해시 함수를 읽는다.
source "${DEPLOY:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}/runtime-source-inputs.sh"
